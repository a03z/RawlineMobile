package app.rawline.mobile.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SpeedDisplayData(
    // Bytes per second
    var txRateProxy: Long = 0L,
    var rxRateProxy: Long = 0L,
    var txRateDirect: Long = 0L,
    var rxRateDirect: Long = 0L,

    // Bytes for the current session.
    // txTotal/rxTotal are proxy tags. Direct is separate and is omitted from the notification.
    var txTotal: Long = 0L,
    var rxTotal: Long = 0L,
    var txTotalDirect: Long = 0L,
    var rxTotalDirect: Long = 0L,
) : Parcelable
