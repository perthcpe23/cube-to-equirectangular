#!/bin/bash

javac -d bin src/com/longdo/pano/Cube2Erect.java
jar cfe Cube2Erect.jar com.longdo.pano.Cube2Erect -C bin com/longdo/pano/Cube2Erect.class
# java -cp bin com.longdo.pano.Cube2Erect f0_back.jpg f0_bottom.jpg f0_front.jpg f0_left.jpg f0_right.jpg f0_top.jpg output.jpg