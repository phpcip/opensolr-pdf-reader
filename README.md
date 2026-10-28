# Opensolr PDF Reader

A very simple PDF reader for Android. It opens a PDF and gets out of the way.

- The pages fill the screen, one under the other. Nothing on the edges; a tap shows a small bar with the page number and Share, another tap hides it.
- Pinch or double tap to zoom, drag and fling to move. A zoomed page is drawn again at screen resolution.
- Long press a word to select it, keep the finger down to select up to another word, then Copy. Whole page selects every word on the page. On a page that is a picture, its text is read on the phone, only then and only for that page (Tesseract, English and Romanian).
- Share: Add to Dropbox first when the Dropbox app is on the phone, then the three apps you share PDFs to most, then the system share sheet.
- Set it as the default PDF viewer: open any PDF, pick Opensolr PDF Reader and tap Always.

## Network

The Google Play build has no network permission at all. The GitHub build uses the network for one thing only: checking the latest GitHub release once a day and installing it when you tap Update. Your PDFs never leave the phone unless you share them.

## Build

```
./gradlew assembleGithubRelease bundlePlayRelease
```

A `signing.properties` next to `build.gradle.kts` signs the release build (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). It is never committed.

## Third-party

| Part | Licence |
|---|---|
| [PDFium](https://pdfium.googlesource.com/pdfium/) through [PdfiumAndroidKt](https://github.com/johngray1965/PdfiumAndroidKt) | BSD 3-Clause / Apache 2.0 |
| [Tesseract](https://github.com/tesseract-ocr/tesseract) through [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) | Apache 2.0 |
| [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) `eng`, `ron` | Apache 2.0 |
| [Space Grotesk](https://github.com/floriankarsten/space-grotesk) | SIL Open Font License 1.1 |

## Licence

MIT, see [LICENSE](LICENSE).
