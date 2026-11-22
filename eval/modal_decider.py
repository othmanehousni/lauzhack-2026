"""Replay the eval scripts through the Kotlin ModelAgent (ch.lauzhack.app.decider.Replay) on a Modal CPU container, so
no model loads on this Mac. The test classes and their classpath are compiled here with Gradle, as eval/modal_engine.py
does; the decks are drawn in the container by fixtures/make_decks.py. The embedder comes from the mole-engine-models
Volume, the decision model from the decider Volume, both mounted read-only.

  export JAVA_HOME=<a JDK 21> ANDROID_HOME=<the Android SDK>
  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run eval/modal_decider.py                                  every script, with r4/decider_q8_ekv1024.litertlm, the installed model
  modal run eval/modal_decider.py --model r1/decider.litertlm --scripts talk,web --out replay.txt
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

import modal

REPO = Path(__file__).parent.parent
STAGE = Path(tempfile.gettempdir()) / "mole-decider-classpath"
INIT = """gradle.projectsEvaluated {
    def p = rootProject.project(':composeApp')
    p.tasks.register('deciderClasspath') {
        def cp = p.tasks.named('desktopTest').get().classpath
        def out = new File(System.getenv('DECIDER_CLASSPATH'))
        dependsOn cp
        doLast { out.text = cp.files.join('\\n') }
    }
}
"""


def stage() -> None:
    """Compile the desktop test classes and copy them with every jar they need into STAGE: lib/ for jars, d<i>/ for class dirs."""
    with tempfile.TemporaryDirectory() as tmp:
        init, listing = Path(tmp, "classpath.gradle"), Path(tmp, "classpath.txt")
        init.write_text(INIT)
        # At most two Gradle builds at once on this machine.
        subprocess.run('while [ "$(pgrep -f GradleWrapperMain | wc -l)" -ge 2 ]; do sleep 20; done; ./gradlew --no-daemon -q '
                       f'-Pkotlin.compiler.execution.strategy=in-process --no-configuration-cache -I {init} :composeApp:deciderClasspath',
                       shell=True, cwd=REPO, check=True, env=os.environ | {"DECIDER_CLASSPATH": str(listing)})
        entries = [Path(l) for l in listing.read_text().splitlines() if Path(l).exists()]
    shutil.rmtree(STAGE, ignore_errors=True)
    (STAGE / "lib").mkdir(parents=True)
    for i, entry in enumerate(entries):
        if entry.is_dir():
            shutil.copytree(entry, STAGE / f"d{i}")
        elif entry.suffix == ".jar":
            os.link(entry, STAGE / "lib" / f"{i}-{entry.name}")


if modal.is_local() and not os.environ.get("MODAL_DECIDER_STAGED"):
    stage()
    os.environ["MODAL_DECIDER_STAGED"] = "1"

engine_models = modal.Volume.from_name("mole-engine-models").read_only()
decider_models = modal.Volume.from_name("decider").read_only()
image = (modal.Image.from_registry("eclipse-temurin:21-jre-noble", add_python="3.12")
         .pip_install("reportlab>=5.1.0")
         .add_local_dir(STAGE, "/app")
         .add_local_dir(REPO / "eval", "/repo/eval")
         .add_local_dir(REPO / "fixtures", "/repo/fixtures"))
app = modal.App("mole-decider-replay", image=image)


@app.function(cpu=4.0, memory=4096, timeout=3600, volumes={"/models": engine_models, "/decider": decider_models})
def replay(model: str, scripts: str) -> str:
    models = Path("/tmp/models")
    models.mkdir()
    for f in Path("/models").iterdir():
        if f.is_file():
            (models / f.name).symlink_to(f)
    (models / "decider.litertlm").symlink_to(Path("/decider", model))
    subprocess.run(["python", "/repo/fixtures/make_decks.py", "/tmp/library", "--all"], check=True, stdout=subprocess.DEVNULL)
    cp = ":".join(["/app/lib/*", *sorted(str(d) for d in Path("/app").glob("d*"))])
    env = os.environ | {"MOLE_CHECK": "decider", "MOLE_MODELS": str(models), "MOLE_CACHE": "/tmp/cache", "MOLE_LIBRARY": "/tmp/library",
                        "MOLE_REPO": "/repo"} | ({"MOLE_SCRIPTS": scripts} if scripts else {})
    run = subprocess.Popen(["java", "-Xmx1g", "-Djava.awt.headless=true", "-cp", cp, "org.junit.runner.JUnitCore", "ch.lauzhack.app.decider.Replay"],
                           env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out = []
    for line in run.stdout:
        print(line, end="")
        out.append(line)
    if run.wait():
        raise RuntimeError("Replay failed:\n" + "".join(out[-60:]))
    return "".join(out)


@app.local_entrypoint()
def main(model: str = "r4/decider_q8_ekv1024.litertlm", scripts: str = "", out: str = ""):
    text = replay.remote(model, scripts)
    if out:
        Path(out).write_text(text)
        print(f"wrote {out}")
