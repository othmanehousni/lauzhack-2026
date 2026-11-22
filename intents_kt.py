"""Write intents.py's training sentences as Kotlin for the app's intent classifier: python3 intents_kt.py"""
from pathlib import Path

from intents import INTENTS

OUT = Path(__file__).parent / "composeApp/src/commonMain/kotlin/ch/lauzhack/app/agent/Examples.kt"


def kt(s: str) -> str:
    return '"' + "".join("\\" + c if c in '\\"$' else f"\\u{ord(c):04x}" if ord(c) < 32 else c for c in s) + '"'


lines = ["// Generated from intents.py by intents_kt.py. Change intents.py and rerun `python3 intents_kt.py`.",
         "package ch.lauzhack.app.agent", "", "/** The intent classifier's labels, in intents.py's order, each with its example sentences. */",
         "val EXAMPLES: List<Pair<String, List<String>>> = listOf("]
for label, texts in INTENTS.items():
    lines += [f"    {kt(label)} to listOf(", *(f"        {kt(t)}," for t in texts), "    ),"]
OUT.write_text("\n".join([*lines, ")", ""]))
print(f"{OUT}: {len(INTENTS)} labels, {sum(map(len, INTENTS.values()))} sentences")
