# Vendor Libraries

This directory contains vendor libraries that are included directly in the core project, rather than being downloaded from a remote repository. This is mainly to avoid dependency hell (I had a lot of trouble trying to include them properly in the build.gradle.kts files) and to keep the project self-contained.

## Querz/NBT

The library used for reading and writing NBT and MCA files.
