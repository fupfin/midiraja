#----------------------------------------------------------------
# Generated CMake target import file for configuration "Release".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "libADLMIDI::ADLMIDI_shared" for configuration "Release"
set_property(TARGET libADLMIDI::ADLMIDI_shared APPEND PROPERTY IMPORTED_CONFIGURATIONS RELEASE)
set_target_properties(libADLMIDI::ADLMIDI_shared PROPERTIES
  IMPORTED_LOCATION_RELEASE "${_IMPORT_PREFIX}/lib/libADLMIDI.1.6.2.dylib"
  IMPORTED_SONAME_RELEASE "@rpath/libADLMIDI.1.dylib"
  )

list(APPEND _cmake_import_check_targets libADLMIDI::ADLMIDI_shared )
list(APPEND _cmake_import_check_files_for_libADLMIDI::ADLMIDI_shared "${_IMPORT_PREFIX}/lib/libADLMIDI.1.6.2.dylib" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
