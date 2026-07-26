minSdk is 26, so the adaptive icon in mipmap-anydpi-v26 covers every
supported device and no density-specific PNG fallbacks are required. This
folder is kept only so the resource tree matches the conventional layout;
drop a legacy ic_launcher.png here if you ever lower minSdk below 26.
