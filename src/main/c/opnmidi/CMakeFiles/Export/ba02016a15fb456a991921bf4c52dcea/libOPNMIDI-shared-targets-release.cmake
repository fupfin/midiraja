#----------------------------------------------------------------
# Generated CMake target import file for configuration "Release".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "libOPNMIDI::OPNMIDI_shared" for configuration "Release"
set_property(TARGET libOPNMIDI::OPNMIDI_shared APPEND PROPERTY IMPORTED_CONFIGURATIONS RELEASE)
set_target_properties(libOPNMIDI::OPNMIDI_shared PROPERTIES
  IMPORTED_LOCATION_RELEASE "${_IMPORT_PREFIX}/lib/libOPNMIDI.1.6.1.dylib"
  IMPORTED_SONAME_RELEASE "@rpath/libOPNMIDI.1.dylib"
  )

list(APPEND _cmake_import_check_targets libOPNMIDI::OPNMIDI_shared )
list(APPEND _cmake_import_check_files_for_libOPNMIDI::OPNMIDI_shared "${_IMPORT_PREFIX}/lib/libOPNMIDI.1.6.1.dylib" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
