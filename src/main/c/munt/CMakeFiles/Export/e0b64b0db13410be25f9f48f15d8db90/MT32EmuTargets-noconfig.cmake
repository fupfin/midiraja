#----------------------------------------------------------------
# Generated CMake target import file.
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "MT32Emu::mt32emu" for configuration ""
set_property(TARGET MT32Emu::mt32emu APPEND PROPERTY IMPORTED_CONFIGURATIONS NOCONFIG)
set_target_properties(MT32Emu::mt32emu PROPERTIES
  IMPORTED_LOCATION_NOCONFIG "${_IMPORT_PREFIX}/lib/libmt32emu.2.8.0.dylib"
  IMPORTED_SONAME_NOCONFIG "@rpath/libmt32emu.2.dylib"
  )

list(APPEND _cmake_import_check_targets MT32Emu::mt32emu )
list(APPEND _cmake_import_check_files_for_MT32Emu::mt32emu "${_IMPORT_PREFIX}/lib/libmt32emu.2.8.0.dylib" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
