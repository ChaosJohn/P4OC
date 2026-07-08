package dev.blazelight.p4oc.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.blazelight.p4oc.MainActivity
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.ui.permission.PermissionDisplayFormatter

private const val TAG = "NotificationHelper"

class NotificationHelper constructor(
    private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "user_input_required"
        const val COMPLETION_CHANNEL_ID = "assistant_completed"

        private const val PERMISSION_ID_MASK = 0x40000000
        private const val QUESTION_ID_MASK = 0x20000000
        private const val COMPLETION_ID_MASK = 0x10000000

        private fun permissionNotificationId(sessionId: String): Int =
            (sessionId.hashCode() and 0x0FFFFFFF) or PERMISSION_ID_MASK

        private fun questionNotificationId(sessionId: String): Int =
            (sessionId.hashCode() and 0x0FFFFFFF) or QUESTION_ID_MASK

        private fun completionNotificationId(sessionId: String): Int =
            (sessionId.hashCode() and 0x0FFFFFFF) or COMPLETION_ID_MASK
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val inputChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_user_input_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_user_input_desc)
                enableVibration(true)
            }
            val completionChannel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                context.getString(R.string.notification_channel_completion_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_completion_desc)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(inputChannel)
            notificationManager.createNotificationChannel(completionChannel)
        }
    }

    fun showPermissionNotification(sessionId: String, permission: Permission) {
        val notificationId = permissionNotificationId(sessionId)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sessionId", sessionId)
            putExtra("type", "permission")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = PermissionDisplayFormatter.title(context, permission)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_permission_required))
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Notification post failed: ${e.message}", e)
        }
    }

    fun showQuestionNotification(sessionId: String, question: String?) {
        val notificationId = questionNotificationId(sessionId)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sessionId", sessionId)
            putExtra("type", "question")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val questionText = question ?: context.getString(R.string.notification_question_fallback)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_question_title))
            .setContentText(questionText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(questionText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Notification post failed: ${e.message}", e)
        }
    }

    fun showCompletionNotification(sessionId: String, sessionTitle: String?) {
        val notificationId = completionNotificationId(sessionId)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sessionId", sessionId)
            putExtra("type", "completion")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_completion_title))
            .setContentText(sessionTitle ?: context.getString(R.string.notification_completion_fallback))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Notification post failed: ${e.message}", e)
        }
    }

    fun clearNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
