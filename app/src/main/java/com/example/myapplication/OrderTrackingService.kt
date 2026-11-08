package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 주문이 진행 중인 동안만 켜 두는 포그라운드 서비스.
 *
 * 왜 필요한가: 경유지를 추가하면 내비가 앞으로 나와 이 앱이 백그라운드가 되고,
 * 약 1분 뒤 Android가 프로세스를 얼려(freezing) 5초 조회 루프가 멈춤 (2026-11-08 에뮬레이터에서 확인).
 * 포그라운드 서비스가 떠 있으면 프로세스가 얼지 않아 주행 중 ETA 재계산·상태 동기화가 계속됨.
 * 실제 조회는 MainActivity의 루프가 하고, 이 서비스는 프로세스를 깨어 있게만 함.
 */
class OrderTrackingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "도착 시간에 맞춰 매장에 주문을 전달하고 있어요"
        startForeground(NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "주문 진행", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DriveOrder 주문 진행 중")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "order_tracking"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            context.startForegroundService(
                Intent(context, OrderTrackingService::class.java).putExtra(EXTRA_TEXT, text)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderTrackingService::class.java))
        }
    }
}
