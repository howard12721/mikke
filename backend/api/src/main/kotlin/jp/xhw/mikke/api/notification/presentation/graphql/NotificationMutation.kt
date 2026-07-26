package jp.xhw.mikke.api.notification.presentation.graphql

import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import jp.xhw.mikke.api.graphql.apiRequestContext
import jp.xhw.mikke.api.notification.application.NotificationApiService

class NotificationMutation(
    private val notificationApiService: NotificationApiService,
) : Mutation {
    suspend fun registerPushInstallation(
        input: RegisterPushInstallationInput,
        environment: DataFetchingEnvironment,
    ): PushInstallation =
        notificationApiService
            .registerPushInstallation(
                context = environment.apiRequestContext(),
                deviceId = input.deviceId,
                platform = input.platform,
                firebaseInstallationId = input.firebaseInstallationId,
            ).toGraphQl()

    suspend fun deletePushInstallation(
        input: DeletePushInstallationInput,
        environment: DataFetchingEnvironment,
    ): Boolean {
        notificationApiService.deletePushInstallation(
            context = environment.apiRequestContext(),
            deviceId = input.deviceId,
            platform = input.platform,
        )
        return true
    }
}
