; Desktop shortcut is controlled by the finish-page checkbox (Tauri default).
; Do NOT force-create it here — that ignored the user's unchecked box.
; Clean up leftovers from older installers that always wrote a Desktop link.

!macro NSIS_HOOK_POSTINSTALL
  ; Previous crate name leaked into the installed exe.
  Delete "$INSTDIR\myoc_windows.exe"
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  Delete "$DESKTOP\${PRODUCTNAME}.lnk"
!macroend
