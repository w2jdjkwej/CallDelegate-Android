package com.example.calldelegate.telecom.recording;

import android.os.ParcelFileDescriptor;

interface IShizukuCaptureService {
    ParcelFileDescriptor startCapture(
        String serverPath,
        String socketId,
        String audioSource,
        int audioBitRate
    ) = 1;

    void stopCapture() = 2;

    String startUplinkInjection(int sampleRateHz) = 3;

    int writeUplinkInjection(in byte[] pcm16LittleEndian) = 4;

    String stopUplinkInjection() = 5;

    void destroy() = 16777114;
}
