package ch.lauzhack.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ch.lauzhack.app.link.PhoneState
import ch.lauzhack.app.mole.MolePhone
import kotlinx.coroutines.flow.StateFlow

/** The phone: what the laptop says, or, while there is no laptop to say it, that the mole is looking for it. The laptop
 * serves the phone only during a call, so a laptop asleep and one out of reach look the same from here. */
@Composable
fun App(phone: StateFlow<PhoneState>, onPick: (Int) -> Unit) {
    val s by phone.collectAsState()
    val line = if (s.connected) s.line else MoleLine(MoleState.Tunneling, "Looking for your laptop…")
    MolePhone(line, s.onScreenName, s.thumbnail, s.phoneLeads, s.meeting, onPick)
}
