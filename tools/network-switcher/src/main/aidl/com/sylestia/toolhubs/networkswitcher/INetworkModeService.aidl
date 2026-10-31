package com.sylestia.toolhubs.networkswitcher;

interface INetworkModeService {
    void destroy() = 16777114;
    String reconnect5G() = 1;
    String switchDefaultDataSubscription(int subscriptionId) = 2;
    String openSamsungBandSelection() = 3;
    String setThermalStatusOverride(int status) = 4;
    String resetThermalStatus() = 5;
    String setWifiEnabled(boolean enable) = 6;
}
