package ch.lauzhack.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ch.lauzhack.app.link.PhoneState
import ch.lauzhack.app.mole.MolePhone
import kotlinx.coroutines.flow.StateFlow

/** The phone: what the laptop says, or, while there is no laptop to say it, that the mole is looking for it. */
@Composable
fun App(phone: StateFlow<PhoneState>, onPick: (Int) -> Unit) {
    val s by phone.collectAsState()
    MolePhone(s.line, s.onScreenName, s.thumbnail, s.phoneLeads, s.meeting, onPick, connected = s.connected)
}
