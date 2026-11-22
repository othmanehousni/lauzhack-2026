"""Run the Kotlin model engine (ch.lauzhack.app.engine, LiteRT-LM) on a Modal CPU container, so no model loads on this Mac.
The test classes and their runtime classpath are compiled here with Gradle, then EngineCheck runs in a Linux x86_64 JVM
(the litertlm-jvm jar carries that native too). Models are downloaded by the app's own ModelStore onto a Volume.

  export JAVA_HOME=<a JDK 21> ANDROID_HOME=<the Android SDK>
  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run eval/modal_engine.py                                     the embedder and Gemma 4 E2B client checks
  modal run eval/modal_engine.py --checks embedder                   one of them
  modal run eval/modal_engine.py --texts texts.json --out v.f32      vectors of a JSON array of strings, as little-endian
                                                                     float32, n x 768, in the same order
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

import modal

REPO = Path(__file__).parent.parent
STAGE = Path(tempfile.gettempdir()) / "mole-engine-classpath"
INIT = """gradle.projectsEvaluated {
    def p = rootProject.project(':composeApp')
    p.tasks.register('engineClasspath') {
        def cp = p.tasks.named('desktopTest').get().classpath
        def out = new File(System.getenv('ENGINE_CLASSPATH'))
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
                       f'-Pkotlin.compiler.execution.strategy=in-process --no-configuration-cache -I {init} :composeApp:engineClasspath',
                       shell=True, cwd=REPO, check=True, env=os.environ | {"ENGINE_CLASSPATH": str(listing)})
        entries = [Path(l) for l in listing.read_text().splitlines() if Path(l).exists()]
    shutil.rmtree(STAGE, ignore_errors=True)
    (STAGE / "lib").mkdir(parents=True)
    for i, entry in enumerate(entries):
        if entry.is_dir():
            shutil.copytree(entry, STAGE / f"d{i}")
        elif entry.suffix == ".jar":
            os.link(entry, STAGE / "lib" / f"{i}-{entry.name}")


if modal.is_local() and not os.environ.get("MODAL_ENGINE_STAGED"):
    stage()
    os.environ["MODAL_ENGINE_STAGED"] = "1"

models = modal.Volume.from_name("mole-engine-models", create_if_missing=True)
image = modal.Image.from_registry("eclipse-temurin:21-jre-noble", add_python="3.12").add_local_dir(STAGE, "/app")
app = modal.App("mole-engine", image=image)


@app.function(cpu=4.0, memory=8192, timeout=3600, volumes={"/models": models})
def check(name: str, texts: bytes = b"[]") -> bytes:
    Path("/tmp/texts.json").write_bytes(texts)
    cp = ":".join(["/app/lib/*", *sorted(str(d) for d in Path("/app").glob("d*"))])
    env = os.environ | {"MOLE_CHECK": name, "MOLE_MODELS": "/models", "MOLE_CACHE": "/tmp/cache",
                        "MOLE_TEXTS": "/tmp/texts.json", "MOLE_VECTORS": "/tmp/vectors.f32"}
    result = subprocess.run(["java", "-Xmx1g", "-cp", cp, "org.junit.runner.JUnitCore", "ch.lauzhack.app.engine.EngineCheck"], env=env)
    models.commit()
    if result.returncode:
        raise RuntimeError(f"EngineCheck {name} failed")
    return Path("/tmp/vectors.f32").read_bytes() if name == "vectors" else b""


@app.local_entrypoint()
def main(checks: str = "embedder,client", texts: str = "", out: str = "vectors.f32"):
    if texts:
        Path(out).write_bytes(check.remote("vectors", Path(texts).read_bytes()))
        print(f"wrote {out}")
        return
    for name in checks.split(","):
        check.remote(name)
