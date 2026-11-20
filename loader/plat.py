"""Platform helpers for the build scripts (Windows and Linux).

  JAVA          java executable: env ALLOY_JAVA, else JAVA_HOME, else a JDK 25 in the usual install folders, else PATH
  LOW           creationflags for child processes: BELOW_NORMAL on Windows, 0 elsewhere
  tool(jdk, n)  javac / jar / java in a JDK bin folder, with .exe on Windows
"""
import glob, os, shutil
from pathlib import Path

WIN = os.name == "nt"
LOW = 0x00004000 if WIN else 0      # BELOW_NORMAL_PRIORITY_CLASS, inherited by java
EXE = ".exe" if WIN else ""


def _default_java():
    if os.environ.get("ALLOY_JAVA"):
        return Path(os.environ["ALLOY_JAVA"])
    homes = [os.environ.get("JAVA_HOME")]
    if WIN:
        for pat in (r"C:\Program Files\Eclipse Adoptium\jdk-25*", r"C:\Program Files\Java\jdk-25*", r"C:\Program Files\Microsoft\jdk-25*"):
            homes += sorted(glob.glob(pat))[::-1]
    else:
        homes += sorted(map(str, Path("/usr/lib/jvm").glob("java-25-openjdk*")))
    for home in homes:
        if home and (Path(home) / "bin" / ("javac" + EXE)).exists():
            return Path(home) / "bin" / ("java" + EXE)
    w = shutil.which("java")
    return Path(w) if w else Path("java" + EXE)


JAVA = _default_java()


def tool(jdk, name):
    return Path(jdk) / (name + EXE)
