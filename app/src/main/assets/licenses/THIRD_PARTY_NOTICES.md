# Third-party notices

- Original xiaozhi-thinksmart-client, justhalfbit, MIT. Original LICENSE retained at repository root.
- Concentus Java, lostromb/contributors, BSD 3-clause. Source vendored in app/src/main/java/org/concentus; license in third-party. https://github.com/lostromb/concentus
- sherpa-onnx 2.0.1, k2-fsa/contributors, Apache-2.0. Official Android AAR in app/libs. https://github.com/k2-fsa/sherpa-onnx
- ICU 78.3 / Unicode, Unicode License v3: the bundled han-pinyin-tone.txt was generated with ICU Han-Latin; license in third-party/ICU-LICENSE.txt and bundled app licenses. https://icu.unicode.org/
- Chinese keyword models: app/src/main/assets/kws-zh (imported from the original project), wenetspeech Zipformer 3.3M, Apache-2.0; upstream model README included.
- OkHttp 3.12.13 / Okio, Square/contributors, Apache-2.0, resolved by Gradle.
- LobeHub icons 1.95.1, LobeHub, MIT; model-provider icons converted from the upstream project, license in third-party/LOBE-ICONS-MIT.txt. https://github.com/lobehub/lobe-icons
- Muse model icon: official Muse Gadgets favicon, used to identify the Muse service. Source: https://gadgets.muse.ai/gadgets/favicon-mark-light.png. Muse branding belongs to its respective owner.
- Kotlin standard library 2.1.0, JetBrains, Apache-2.0, resolved by Gradle.

This is an independent Android port; no Lenovo or model-provider affiliation.

唤醒复核使用的 `app/src/main/assets/kws-zh/pinyin.txt` 来自原项目固定提交
`0e97cc11738ad38c0b21ec2db6cdb39d2c75795e` 的 `thinksmart_client/pinyin.txt`；
`WakeMatcher` 参考同提交的 `wake_verify.py` 移植，沿用原项目 MIT 许可（见 `ORIGINAL-LICENSE`）。
g