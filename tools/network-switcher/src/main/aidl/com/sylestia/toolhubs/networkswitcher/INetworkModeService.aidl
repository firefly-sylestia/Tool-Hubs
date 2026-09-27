package com.sylestia.toolhubs.networkswitcher;

interface INetworkModeService {
    void destroy() = 16777114;
    String reconnect5G() = 1;
    String switchDefaultDataSubscription(int subscriptionId) = 2;
}
