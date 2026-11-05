# Windows path note

Kotlin/AGP test class loading in a path containing ! failed to resolve the main classes JAR in the original workspace and in the extracted first build.
The successful export test used an exact source copy in a path without !. Clone to a normal path (e.g. C:/src/android-agent-skill-store).
Build input hashes are retained privately. The public component does not carry the product-specific compile-main-into-test workaround.
