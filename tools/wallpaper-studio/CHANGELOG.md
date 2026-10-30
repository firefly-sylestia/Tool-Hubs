# Changelog

## 0.4.0
- Replaced crop presets with a freeform editor supporting drag-to-pan, pinch-to-zoom and twist-to-rotate gestures.
- Added precise zoom and rotation sliders plus a one-tap reset.
- Persists each scene's pan, zoom and rotation and applies the same transform to the final wallpaper bitmap.


## 0.3.0
- Replaced the generic shared glass look with a quieter Samsung One UI-inspired visual system, custom navigation, larger page headers and refined controls.
- Added Fill, Fit, Stretch and Original size placement modes with adjustable horizontal/vertical focal position and a device-shaped preview.
- Composes the final wallpaper at device dimensions and respects EXIF orientation.
- New scenes default to manual-only; automatic switching is an explicit per-scene option.
- Restores saved static Home/Lock wallpapers when readable; on Android 13+, where the active wallpaper cannot be read by ordinary apps, restores the device's built-in default when paired temporary conditions end.
- Added screen on/off, airplane mode, battery saver, battery recovered and wired headset triggers.
- Added a manual Restore previous wallpaper action.

## 0.2.0
- Added optional Shizuku service status and permission controls.
- Added bounded scene-image preloading and duplicate-event suppression.

## 0.1.0
- Initial Wallpaper Studio module with local image library, scene automation and time schedules.
