package app.rawline.mobile.bg.proto

import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.RouteManager
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.database.SettingsMapper
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.config.ConfigGenerator
import app.rawline.mobile.outbound.config.GeneratedConfig
import app.rawline.mobile.outbound.config.GeneratorSettings
import app.rawline.mobile.outbound.config.PortLease
import app.rawline.mobile.outbound.config.ProfileProvider
import app.rawline.mobile.outbound.config.RoutingInput
import app.rawline.mobile.outbound.config.TestCandidate
import app.rawline.mobile.route.RouteDns
import app.rawline.mobile.route.RouteProfile

/**
 * The config generator wired to the app: profiles come from the profile table, the settings and the build-time
 * globals from DataStore through [SettingsMapper], the route profile of the profile's group and the rule-set list from
 * RouteManager (main configs only; test configs read only the current profile's own DNS), the landing / front proxy
 * from the profile's group.
 */
object CoreConfigs {

    /** Stored profiles by id, each row parsed once per generation; group pairs resolved once per profile id too. */
    private class DatabaseProfiles(private val geoFallback: app.rawline.mobile.outbound.json.JsonObject = app.rawline.mobile.outbound.json.JsonObject()) : ProfileProvider {
        private val cache = HashMap<Long, Outbound?>()
        private val groupCache = HashMap<Long, Pair<Long, Long>>()
        override fun get(id: Long): Outbound? = cache.getOrPut(id) {
            SagerDatabase.proxyDao.getById(id)?.let { prepareOutbound(it, geoFallback) }
        }
        override fun groupProxies(profileId: Long): Pair<Long, Long> = groupCache.getOrPut(profileId) {
            val groupId = SagerDatabase.proxyDao.getById(profileId)?.groupId ?: -1L
            if (groupId > 0) groupProxies(groupId).let { it.first to it.second } else -1L to -1L
        }
    }

    /** Geo sources belong to the subscription even when its routing switch is off. */
    fun prepareOutbound(profile: ProxyEntity, fallback: app.rawline.mobile.outbound.json.JsonObject = app.rawline.mobile.outbound.json.JsonObject()): Outbound {
        val outbound = profile.outbound
        if (outbound !is app.rawline.mobile.outbound.types.Custom || !outbound.isXrayFullConfig()) return outbound
        val options = SagerDatabase.groupDao.getById(profile.groupId)?.subOptions
        val document = options?.takeIf { it.routingProvider.isNotBlank() }?.let {
            app.rawline.mobile.route.ProviderRouteProfiles.sourceDocument(it.providerRouting, it.providerRoutingProfiles)
        }?.takeIf { it.isNotEmpty() } ?: fallback
        return app.rawline.mobile.outbound.types.Custom().apply {
            parseFromJson(outbound.exportToJson())
            config = app.rawline.mobile.route.ProviderGeoAssets.xrayConfig(document, config)
        }
    }

    /** [dns]: the routing profile's own DNS, null for the global one; [settings] must be mapped with the same. */
    fun generator(
        routing: RoutingInput = RoutingInput.DEFAULT,
        settings: GeneratorSettings = SettingsMapper.generatorSettings(),
        dns: RouteDns? = null,
    ): ConfigGenerator = ConfigGenerator(DatabaseProfiles(), settings, SettingsMapper.buildContext(dns), routing)

    /**
     * The main config of [profile]; throws with the generator's message when it cannot be built. [deferRuleSets]
     * is GeneratorSettings.deferRuleSets, for a start the user let go without its rule-sets. A config that will run
     * leases its bridge ports to [ports] (ConfigGenerator.build). [route] is the routing profile of the profile's group.
     */
    fun buildMain(
        profile: ProxyEntity,
        deferRuleSets: Boolean = false,
        ports: PortLease? = null,
        route: RouteProfile = RouteManager.routeFor(profile.groupId),
    ): GeneratedConfig {
        val group = SagerDatabase.groupDao.getById(profile.groupId)
        val options = group?.subOptions
        if (options != null && options.routingProvider.isNotBlank() && options.providerRoutingEnabled && route.id == RouteManager.current().id) {
            try {
                return buildMainWith(profile, deferRuleSets, ports, route)
            } catch (e: Exception) {
                // A provider profile that cannot be used must not cost the connection: the subscription's own
                // routing goes off and the client's takes over, as when it was never enabled. Kept on when the
                // profile fails anyway, as the provider's routing was not the cause then.
                group.subOptions = options.copy(providerRoutingEnabled = false)
                SagerDatabase.groupDao.update(group)
                val fallback = runCatching { buildMainWith(profile, deferRuleSets, ports, route) }
                if (fallback.isFailure) {
                    group.subOptions = options
                    SagerDatabase.groupDao.update(group)
                    throw e
                }
                moe.matsuri.nb4a.utils.CoreLog.write("[Warning] Happ / Incy routing of \"${group.name}\" is unavailable (${e.message}); turned off, the client's routing is used")
                return fallback.getOrThrow()
            }
        }
        return buildMainWith(profile, deferRuleSets, ports, route)
    }

    private fun buildMainWith(profile: ProxyEntity, deferRuleSets: Boolean, ports: PortLease?, groupRoute: RouteProfile): GeneratedConfig {
        val (landing, front, groupName) = groupProxies(profile.groupId)
        val options = SagerDatabase.groupDao.getById(profile.groupId)?.subOptions
        // The group's own routing profile wins over the provider's routing; the global one gives way to it.
        val chosenRoute = groupRoute.takeIf { it.id != RouteManager.current().id && !it.is_raw }
        val bound = options?.takeIf { it.routingProvider.isNotBlank() && it.providerRoutingEnabled }
        val route = when {
            chosenRoute != null -> chosenRoute.copy()
            bound?.providerRouting == "off" -> app.rawline.mobile.route.RouteProfile.defaultProfile()
            bound != null -> app.rawline.mobile.route.ProviderRouting.profile(
                app.rawline.mobile.outbound.json.JsonInput.parseObjectOrNull(bound.providerRouting) ?: app.rawline.mobile.outbound.json.JsonObject(), bound.routingProvider)
            else -> RouteManager.current().copy()
        }
        // Provider routing below still wins over the routing profile's own DNS, which wins over Settings > DNS.
        val dns = route.activeDns()
        var settings = SettingsMapper.generatorSettings(dns).copy(deferRuleSets = deferRuleSets)
        var routing = SettingsMapper.routingInput(route)
        val fullJson = profile.outbound.isXrayFullConfig()
        val providerRouting=app.rawline.mobile.route.ProviderRouting.stored(route)
        app.rawline.mobile.route.ProviderGeoAssets.dropInstalledCopies()
        val profiles = DatabaseProfiles(providerRouting?.second ?: app.rawline.mobile.outbound.json.JsonObject())
        providerRouting?.let { (_, document) ->
            if (fullJson) {
                val tunnel = app.rawline.mobile.route.ProviderRouting.settings(document,settings)
                settings = settings.copy(remoteDns = tunnel.remoteDns, dnsPredefinedRules = tunnel.dnsPredefinedRules, fakeDns = false)
                routing = SettingsMapper.routingInput(app.rawline.mobile.route.RouteProfile.defaultProfile())
            } else {
                settings = app.rawline.mobile.route.ProviderRouting.settings(document, settings)
                val catalog = app.rawline.mobile.route.ProviderGeoAssets.resolve(route, document, routing.catalog)
                routing = RoutingInput(route, catalog, routing.selectors, routing.memberCheck)
            }
        }
        var context = SettingsMapper.buildContext(dns)
        if (app.rawline.mobile.outbound.config.TransportIpPolicy.ipv4Only(profile.outbound, profiles)) {
            settings = app.rawline.mobile.outbound.config.TransportIpPolicy.settings(settings)
            context = app.rawline.mobile.outbound.config.TransportIpPolicy.context(context)
            moe.matsuri.nb4a.utils.CoreLog.write("[Info] ${profile.outbound.displayType()} uses IPv4-only tunnel and DNS; saved IPv6 preference is unchanged")
        }
        moe.matsuri.nb4a.utils.CoreLog.write("[Info] "+DnsDiagnostics.summary(settings,providerRouting!=null))
        val generated = ConfigGenerator(profiles, settings, context, routing).build(profile.id, landing, front, ports, groupName)
        if (!generated.ok) error(generated.error ?: "config generation failed")
        return generated
    }

    /** One test config for every candidate at once, each under its own group's landing / front proxy. */
    fun buildTest(profileIds: List<Long>, ports: PortLease? = null): GeneratedConfig {
        val groups = HashMap<Long, Triple<Long, Long, String>>()
        val candidates = profileIds.map { id ->
            val groupId = SagerDatabase.proxyDao.getById(id)?.groupId ?: -1L
            val (landing, front, groupName) = groups.getOrPut(groupId) { groupProxies(groupId) }
            TestCandidate(id, landing, front, groupName)
        }
        val dns = RouteManager.customDns(DataStore.currentRouteId)
        val fallback = app.rawline.mobile.route.ProviderRouting.stored(RouteManager.current())?.second
            ?: app.rawline.mobile.outbound.json.JsonObject()
        return ConfigGenerator(DatabaseProfiles(fallback), SettingsMapper.generatorSettings(dns), SettingsMapper.buildContext(dns)).buildTest(candidates, ports)
    }

    /** (landing_proxy_id, front_proxy_id, name) of a group, -1 when unset (any id <= 0 is "none"). */
    private fun groupProxies(groupId: Long): Triple<Long, Long, String> {
        val group: ProxyGroup = (if (groupId > 0) SagerDatabase.groupDao.getById(groupId) else null) ?: return Triple(-1L, -1L, "")
        val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
        val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
        return Triple(landing, front, group.displayName())
    }
}
