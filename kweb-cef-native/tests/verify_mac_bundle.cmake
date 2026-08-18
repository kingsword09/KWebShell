if(NOT DEFINED APP_BUNDLE OR NOT IS_DIRECTORY "${APP_BUNDLE}")
  message(FATAL_ERROR "APP_BUNDLE must identify the assembled KWebShell.app.")
endif()
set(FRAMEWORK
    "${APP_BUNDLE}/Contents/Frameworks/Chromium Embedded Framework.framework")

function(require_relative_symlink path expected_target)
  if(NOT IS_SYMLINK "${path}")
    message(FATAL_ERROR "Required bundle link is missing: ${path}")
  endif()
  file(READ_SYMLINK "${path}" actual_target)
  if(NOT actual_target STREQUAL expected_target)
    message(FATAL_ERROR
      "Bundle link '${path}' targets '${actual_target}', expected '${expected_target}'.")
  endif()
endfunction()

require_relative_symlink(
  "${FRAMEWORK}/Chromium Embedded Framework"
  "Versions/A/Chromium Embedded Framework"
)
require_relative_symlink("${FRAMEWORK}/Libraries" "Versions/A/Libraries")
require_relative_symlink("${FRAMEWORK}/Resources" "Versions/A/Resources")
require_relative_symlink("${FRAMEWORK}/Versions/Current" "A")

foreach(invalid_link
    "${FRAMEWORK}/Versions/A/A"
    "${FRAMEWORK}/Versions/A/Libraries/Libraries"
    "${FRAMEWORK}/Versions/A/Resources/Resources")
  if(IS_SYMLINK "${invalid_link}")
    message(FATAL_ERROR "The CEF framework contains a recursive link: ${invalid_link}")
  endif()
endforeach()

foreach(helper_suffix "" " (Alerts)" " (GPU)" " (Plugin)" " (Renderer)")
  set(helper
      "${APP_BUNDLE}/Contents/Frameworks/KWebShell Helper${helper_suffix}.app")
  if(NOT EXISTS
     "${helper}/Contents/MacOS/KWebShell Helper${helper_suffix}")
    message(FATAL_ERROR "CEF helper executable is missing from '${helper}'.")
  endif()
endforeach()
