# The JS bridge is reached by name from JavaScript, so it must survive shrinking.
-keepclassmembers class nl.tunc.blesonar.MainActivity$Bridge {
    public *;
}
-keepattributes JavascriptInterface
