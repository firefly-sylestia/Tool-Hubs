package com.sylestia.toolhubs.datawarden;

interface INetworkControlService {
    void destroy() = 16777114;
    String setChain3(boolean enabled) = 1;
    String setPackageNetworking(boolean enabled, String packageName) = 2;
    String setPackagesNetworking(boolean enabled, in String[] packageNames) = 3;
}
