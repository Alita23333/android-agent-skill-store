plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
 namespace="dev.alita.androidagent.skillarchive"; compileSdk=35
 defaultConfig { minSdk=31 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
 testOptions.unitTests.isIncludeAndroidResources=true
}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.robolectric:robolectric:4.14.1"); testImplementation("androidx.test:core-ktx:1.6.1") }
