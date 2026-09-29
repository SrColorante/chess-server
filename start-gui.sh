#!/bin/bash
# Fix for Linux Wayland/X11 white screen issues in Java Swing
export _JAVA_AWT_WM_NONREPARENTING=1
export GDK_BACKEND=x11

java -Dsun.java2d.xrender=false -Dsun.java2d.opengl=false -cp bin client.ClientGUI
