#----------------------------------------------------------------
# Generated CMake target import file for configuration "Release".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "libOPNMIDI::OPNMIDI_static" for configuration "Release"
set_property(TARGET libOPNMIDI::OPNMIDI_static APPEND PROPERTY IMPORTED_CONFIGURATIONS RELEASE)
set_target_properties(libOPNMIDI::OPNMIDI_static PROPERTIES
  IMPORTED_LINK_INTERFACE_LANGUAGES_RELEASE "C;CXX"
  IMPORTED_LOCATION_RELEASE "${_IMPORT_PREFIX}/lib/libOPNMIDI.a"
  )

list(APPEND _cmake_import_check_targets libOPNMIDI::OPNMIDI_static )
list(APPEND _cmake_import_check_files_for_libOPNMIDI::OPNMIDI_static "${_IMPORT_PREFIX}/lib/libOPNMIDI.a" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
