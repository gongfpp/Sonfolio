package com.gongfpp.sonfolio.qa;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.TextView;

/** Test APK only: competes for the microphone while visible; samples are discarded. */
public final class MicrophoneProbeActivity extends Activity {
    private volatile boolean reading;
    private AudioRecord recorder;
    private TextView label;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        label = new TextView(this);
        label.setText("麦克风竞争验收\n不保存、不上传音频；返回声迹后立即释放麦克风。");
        label.setTextSize(20);
        label.setPadding(40, 120, 40, 40);
        setContentView(label);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && recorder == null) getWindow().getDecorView().postDelayed(() -> {
            if (hasWindowFocus() && recorder == null && !isFinishing()) startCapture();
        }, 500);
    }
    private void startCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            label.setText("测试未启动：测试 APK 的麦克风权限未授予。"); return;
        }
        try {
        int size = Math.max(8192, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); recorder = null;
            label.setText("测试未启动：系统没有提供可用麦克风，请检查本测试页的录音权限与输入设备。"); return;
        }
        recorder.startRecording(); reading = true;
        label.setText("麦克风竞争验收 · 正在临时采集\n不保存、不上传音频；返回声迹后立即释放麦克风。");
        final AudioRecord current = recorder;
        new Thread(() -> {
            byte[] buffer = new byte[size];
            while (reading && current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                if (current.read(buffer, 0, buffer.length) < 0) break;
            }
        }, "qa-microphone-probe").start();
        } catch (RuntimeException error) {
            releaseCapture();
            label.setText("测试未启动：麦克风被系统拒绝，不能据此判断声迹的静音监测是否有效。");
        }
    }
    @Override protected void onPause() {
        releaseCapture();
        super.onPause();
    }
    private void releaseCapture() {
        reading = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            recorder.release(); recorder = null;
        }
    }
}
