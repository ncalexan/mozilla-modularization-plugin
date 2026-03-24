# Mozilla Modularization Plugin

To try it out:

1.  From `$topsrcdir`, run `git clone https://github.com/ncalexan/mozilla-modularization-plugin ../mozilla-modularization-plugin`.
2.  Check out https://github.com/ncalexan/firefox/tree/bug-XXX-modularize-with-archunit
3.  Arrange an Android `mozconfig`.  Artifact builds are fine.
4.  Run `./mach build`.
5.  Run `./mach gradle ./mach gradle :fenix:archUnitDebug`.
6.  Open the HTML log file: file://$topobjdir/gradle/build/mobile/android/fenix/app/reports/modularization/debug/report.html or similar.
