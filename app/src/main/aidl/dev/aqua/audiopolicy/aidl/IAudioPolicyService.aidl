package dev.aqua.audiopolicy.aidl;

interface IAudioPolicyService {
    int getForceUse() = 0;
    int setForceUse(int config) = 1;
    String getForegroundPackage() = 2;
    // Shizuku's reserved destroy transaction (AIDL adds FIRST_CALL_TRANSACTION).
    void destroy() = 16777114;
}
