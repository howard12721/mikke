package jp.xhw.mikke.services.notification.infrastructure

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import jp.xhw.mikke.services.notification.model.PushMessage
import jp.xhw.mikke.services.notification.worker.InvalidPushRegistrationException
import jp.xhw.mikke.services.notification.worker.PushSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.logging.Level
import java.util.logging.Logger

private const val FCM_ENABLED_ENV = "FCM_ENABLED"
private const val FIREBASE_PROJECT_ID_ENV = "FIREBASE_PROJECT_ID"
private const val FIREBASE_SERVICE_ACCOUNT_JSON_BASE64_ENV = "FIREBASE_SERVICE_ACCOUNT_JSON_BASE64"
private const val POST_UPDATES_CHANNEL_ID = "post_updates"

class FirebasePushSender private constructor(
    private val firebaseApp: FirebaseApp,
    private val messaging: FirebaseMessaging = FirebaseMessaging.getInstance(firebaseApp),
) : PushSender,
    AutoCloseable {
    override suspend fun send(
        firebaseInstallationId: String,
        message: PushMessage,
    ): String =
        withContext(Dispatchers.IO) {
            val firebaseMessage =
                Message
                    .builder()
                    .setFid(firebaseInstallationId)
                    .setNotification(
                        Notification
                            .builder()
                            .setTitle(message.title)
                            .setBody(message.body)
                            .build(),
                    ).setAndroidConfig(
                        AndroidConfig
                            .builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(
                                AndroidNotification
                                    .builder()
                                    .setChannelId(POST_UPDATES_CHANNEL_ID)
                                    .setSound("default")
                                    .build(),
                            ).build(),
                    ).setApnsConfig(
                        ApnsConfig
                            .builder()
                            .setAps(
                                Aps
                                    .builder()
                                    .setSound("default")
                                    .build(),
                            ).build(),
                    ).putData("type", "post_created")
                    .putData("postId", message.postId.toString())
                    .putData("authorUserId", message.authorUserId.toString())
                    .build()

            try {
                messaging.send(firebaseMessage)
            } catch (exception: FirebaseMessagingException) {
                if (
                    exception.messagingErrorCode == MessagingErrorCode.UNREGISTERED ||
                    exception.messagingErrorCode == MessagingErrorCode.INVALID_ARGUMENT
                ) {
                    throw InvalidPushRegistrationException(exception)
                }
                throw exception
            }
        }

    override fun close() {
        firebaseApp.delete()
    }

    companion object {
        private val logger = Logger.getLogger(FirebasePushSender::class.java.name)

        fun fromEnvironmentOrNull(): FirebasePushSender? {
            if (!System.getenv(FCM_ENABLED_ENV)?.toBooleanStrictOrNull().orFalse()) {
                logger.info("FCM delivery is disabled; queued push deliveries will be retained")
                return null
            }

            return try {
                val projectId =
                    System
                        .getenv(FIREBASE_PROJECT_ID_ENV)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: error("$FIREBASE_PROJECT_ID_ENV is not configured")
                val options =
                    FirebaseOptions
                        .builder()
                        .setCredentials(firebaseCredentials())
                        .setProjectId(projectId)
                        .build()
                FirebasePushSender(FirebaseApp.initializeApp(options, "mikke-notification-service"))
            } catch (exception: Exception) {
                logger.log(Level.SEVERE, "FCM is enabled but Firebase Admin initialization failed", exception)
                throw exception
            }
        }

        private fun firebaseCredentials(): GoogleCredentials {
            val encodedServiceAccount =
                System
                    .getenv(FIREBASE_SERVICE_ACCOUNT_JSON_BASE64_ENV)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: return GoogleCredentials.getApplicationDefault()
            val decodedServiceAccount = Base64.getDecoder().decode(encodedServiceAccount)
            return GoogleCredentials.fromStream(ByteArrayInputStream(decodedServiceAccount))
        }
    }
}

private fun Boolean?.orFalse(): Boolean = this ?: false
