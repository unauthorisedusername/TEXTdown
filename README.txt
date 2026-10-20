CODE EDITOR - GitHub APK build project

1. Create a new repository on github.com (Public or Private).
2. Add file > Upload files. Select ALL the files from this folder EXCEPT
   README.txt and WORKFLOW.txt (they can be uploaded too, it is harmless).
   Commit changes.
3. Add file > Create new file. In the name box type exactly:
       .github/workflows/build.yml
   Open WORKFLOW.txt, copy everything, paste it into the editor, Commit.
4. Open the Actions tab. Wait for "Build APK" to finish (3-6 minutes).
5. Open the finished run, scroll to Artifacts, download CodeEditor-APK (a zip),
   extract it, and install the .apk (allow "install unknown apps" if asked).

To edit later: change index.html (your editor), MainActivity.java or
AndroidManifest.xml in GitHub; each commit builds a new APK.

Notes:
- Only press "Run" on code you trust.
- If the build fails, open the failed run, copy the red error lines and send them to Claude.
