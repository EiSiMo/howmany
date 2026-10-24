cmake_minimum_required(VERSION 3.22.1)
project(howmany C)

add_library(memory SHARED memory.c)
# Without debug info, which holds the build's paths, so builds elsewhere match byte for byte.
target_compile_options(memory PRIVATE -g0)
target_link_options(memory PRIVATE -s)
