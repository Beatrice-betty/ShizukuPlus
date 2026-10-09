package af.shizuku.manager.receiver

import af.shizuku.manager.utils.TcpNetworkGuard
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the "Close port" action on the untrusted-network TCP notification (#518). */
class TcpGuardReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != ACTION_CLOSE) return
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TcpNetworkGuard.closePort(context.applicationContext, port)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CLOSE = "af.shizuku.manager.TCP_GUARD_CLOSE"
        const val EXTRA_PORT = "port"
    }
}
