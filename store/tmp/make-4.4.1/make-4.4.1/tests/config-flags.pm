# This is a -*-perl-*- script
#
# Set variables that were defined by configure, in case we need them
# during the tests.

%CONFIG_FLAGS = (
    AM_LDFLAGS      => '-Wl,--export-dynamic',
    AR              => 'ar',
    CC              => 'x86_64-unknown-linux-musl-gcc',
    CFLAGS          => '-g -O2',
    CPP             => 'x86_64-unknown-linux-musl-gcc -E',
    CPPFLAGS        => '-D__GNU_LIBRARY__=1 -Dgetenv=getenv -I/home/samson/Projects/clojv3/./store/musl-1.2.6/include',
    GUILE_CFLAGS    => '',
    GUILE_LIBS      => '',
    LDFLAGS         => '-B/home/samson/Projects/clojv3/./store/musl-1.2.6/lib -L/home/samson/Projects/clojv3/./store/musl-1.2.6/lib -Wl,-rpath,/home/samson/Projects/clojv3/./store/musl-1.2.6/lib -Wl,-dynamic-linker,/home/samson/Projects/clojv3/./store/musl-1.2.6/lib/libc.so',
    LIBS            => '',
    USE_SYSTEM_GLOB => 'no'
);

1;
