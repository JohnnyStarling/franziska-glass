# Original launcher artwork

`franziska-launcher.svg` is an original geometric F mark created for this community release, under the repository's first-party MIT license. It replaces the inherited checklist PNG whose authorship was not established.

The committed Android PNG is a transparent 96 × 96 white mark generated from the same path with no font or external artwork. Regeneration with ImageMagick:

```sh
magick -size 96x96 xc:none -fill white -draw 'path "M20 14H78V28H34V42H66V56H34V82H20Z"' -strip glass-source/android/glass-client/res/drawable/checklist_icon.png
```
