package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.concurrent.CompletableFuture
import kz.bejiihiu.servercore.ws.BackendGateway

/**
 * picks the resolver for a join: ws-backed when the backend link is alive,
 * local config + cache otherwise. ws failures degrade to the local resolver
 * instead of throwing, so a blip never kicks a join handler sideways.
 */
class AccountService(
    private val gateway: BackendGateway,
    private val wsResolver: AccountStateResolver,
    private val localResolver: AccountStateResolver,
) : AccountStateResolver {

    override fun resolve(uuid: UUID, username: String): CompletableFuture<AccountStatus> {
        if (!gateway.connected) return localResolver.resolve(uuid, username)
        return wsResolver.resolve(uuid, username).exceptionally { error ->
            System.err.println("servercore: ws account check failed for $uuid, using local state ($error)")
            localResolver.resolve(uuid, username).join()
        }
    }
}
