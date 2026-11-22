# Vendored Signalsmith headers

These files are copied unchanged from the upstream MIT-licensed projects.

| Directory | Upstream | Version / commit | License |
| --- | --- | --- | --- |
| `signalsmith-stretch/` | https://github.com/Signalsmith-Audio/signalsmith-stretch | header reports 1.3.2, commit `57b93f4e9206a089a45387eaa39bdc9f310d3308` | MIT (`signalsmith-stretch/LICENSE.txt`) |
| `signalsmith-linear/` | https://github.com/Signalsmith-Audio/linear | tag `0.3.1` | MIT (`signalsmith-linear/LICENSE.txt`) |

Linear 0.3.1 is the version pinned by the Signalsmith Stretch 1.3.2 upstream CMake build.

SHA-256 of the main headers:

```text
1188667959AC19DD40C0A6ABBCE694E44705615EC4F0E8DB8AF0F1CFB4C5DEA7  signalsmith-stretch/signalsmith-stretch.h
4E11E85E823AAF2EC9BD5BD6BD55E6665126DE1DE2604AF84574009ED1F3B5F2  signalsmith-linear/fft.h
E7B3D4D98FD4BC4B09740B82A374D4C87AD1D1A6CA28FCFEC71800292B939CA2  signalsmith-linear/stft.h
```

To update, replace both directories, update this table and the checksums, then run `scripts/build.ps1`.
