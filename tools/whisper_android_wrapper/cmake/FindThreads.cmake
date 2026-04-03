if (ANDROID)
    set(Threads_FOUND TRUE)
    set(CMAKE_THREAD_LIBS_INIT "")
    set(CMAKE_USE_PTHREADS_INIT 1)
    set(CMAKE_USE_WIN32_THREADS_INIT 0)
    set(CMAKE_HP_PTHREADS_INIT 0)

    if (NOT TARGET Threads::Threads)
        add_library(Threads::Threads INTERFACE IMPORTED)
        target_compile_options(Threads::Threads INTERFACE -pthread)
        target_link_options(Threads::Threads INTERFACE -pthread)
    endif()

    return()
endif()

message(FATAL_ERROR "This wrapper FindThreads.cmake is intended for Android builds only.")
