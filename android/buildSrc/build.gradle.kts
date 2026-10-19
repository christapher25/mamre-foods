// Build logic only (no library is added to the app): the release manifest rule lives here so that the Gradle task
// verifyReleaseComponents and a unit test call the very same function.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
