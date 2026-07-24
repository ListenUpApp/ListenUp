package com.calypsan.listenup.client.device

import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIUserInterfaceIdiomTV

actual class DeviceContextProvider {
    actual fun detect(): DeviceContext {
        val type =
            when (UIDevice.currentDevice.userInterfaceIdiom) {
                UIUserInterfaceIdiomPhone -> DeviceType.Phone
                UIUserInterfaceIdiomPad -> DeviceType.Tablet
                UIUserInterfaceIdiomTV -> DeviceType.Tv
                else -> DeviceType.Phone
            }
        return DeviceContext(type)
    }
}
