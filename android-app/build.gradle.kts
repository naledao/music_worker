plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.facebook.react") apply false
}

extra["compileSdkVersion"] = 34
extra["minSdkVersion"] = 26
extra["targetSdkVersion"] = 34
extra["kotlinVersion"] = "2.0.21"
extra["REACT_NATIVE_NODE_MODULES_DIR"] = file("node_modules/react-native").absolutePath
