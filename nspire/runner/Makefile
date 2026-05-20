DEBUG = FALSE

GCC = nspire-gcc
LD  = nspire-ld
GENZEHN = genzehn

# Where the Butterscotch repo and its vendor headers live (relative to this Makefile).
ENGINE_SRC = ../../src
VENDOR     = ../../vendor

GCCFLAGS = -Wall -W -marm -std=gnu11
LDFLAGS = -Wl,--gc-sections

ifeq ($(DEBUG),FALSE)
	# Workload is CPU-bound in the bytecode interpreter (soft-float, no FPU),
	# not size-bound — engine.tns is ~0.5 MB vs ~30 MB RAM. -O2 (not -Os)
	# is the right target for the hot VM loop; commonly ~1.3-2x for
	# interpreter-heavy C. Validate Undertale for regressions: a higher
	# opt level can surface latent UB in a large C reimplementation.
	GCCFLAGS += -O2
else
	GCCFLAGS += -O0 -g
endif

# Common include path & defines for builds that pull in the engine.
ENGINE_DEFINES = -DENABLE_BC16 -DENABLE_BC17 -DPLATFORM_NSPIRE
ENGINE_INCLUDES = -I. -I$(ENGINE_SRC) -I$(VENDOR)/stb/ds -I$(VENDOR)/stb/image -I$(VENDOR)/md5

# ---- runner (asset browser; no engine) ----

RUNNER_OBJS = main.o nspire_renderer.o nspire_asset.o

# ---- engine (full Butterscotch engine bootstrap) ----

ENGINE_C_NAMES = \
    binary_reader data_win debug_overlay event_table \
    gml_array gml_method ini input_recording instance \
    int_int_hashmap int_rvalue_hashmap json_reader json_writer \
    noop_audio_system noop_file_system overlay_file_system profiler \
    runner runner_gamepad runner_keyboard spatial_grid string_builder \
    vm vm_builtins

ENGINE_OBJS_FROM_SRC = $(addprefix obj/, $(addsuffix .o, $(ENGINE_C_NAMES)))
ENGINE_VENDOR_OBJS = obj/md5.o obj/stb_ds_impl.o
ENGINE_LOCAL_OBJS  = main_engine.o nspire_renderer.o nspire_asset.o nspire_engine_renderer.o nspire_overlay.o nspire_font5x7.o nspire_file_system.o stb_ds_impl_dummy.o

# Default: build both targets.
all: runner.tns engine.tns

# Generic rule for local sources.
%.o: %.c
	$(GCC) $(GCCFLAGS) -c $<

# Engine sources compile into obj/ to keep separation from local objects.
obj/%.o: $(ENGINE_SRC)/%.c | obj
	$(GCC) $(GCCFLAGS) $(ENGINE_DEFINES) $(ENGINE_INCLUDES) -c $< -o $@

obj/md5.o: $(VENDOR)/md5/md5.c | obj
	$(GCC) $(GCCFLAGS) $(ENGINE_DEFINES) $(ENGINE_INCLUDES) -c $< -o $@

obj/stb_ds_impl.o: stb_ds_impl.c | obj
	$(GCC) $(GCCFLAGS) $(ENGINE_DEFINES) $(ENGINE_INCLUDES) -c $< -o $@

obj:
	mkdir -p obj

# Local engine-aware sources need the engine include path too.
main_engine.o: main_engine.c
	$(GCC) $(GCCFLAGS) $(ENGINE_DEFINES) $(ENGINE_INCLUDES) -c $<

nspire_engine_renderer.o: nspire_engine_renderer.c
	$(GCC) $(GCCFLAGS) $(ENGINE_DEFINES) $(ENGINE_INCLUDES) -c $<

nspire_overlay.o: nspire_overlay.c
	$(GCC) $(GCCFLAGS) $(ENGINE_DEFINES) $(ENGINE_INCLUDES) -c $<

nspire_file_system.o: nspire_file_system.c
	$(GCC) $(GCCFLAGS) $(ENGINE_DEFINES) $(ENGINE_INCLUDES) -c $<

nspire_font5x7.o: nspire_font5x7.c
	$(GCC) $(GCCFLAGS) -c $<

# ---- runner.tns ----

runner.elf: $(RUNNER_OBJS)
	$(LD) $^ -o $@ $(LDFLAGS)

runner.tns: runner.elf
	$(GENZEHN) --input $^ --output $@.zehn --name "butterscotch-runner"
	make-prg $@.zehn $@
	rm $@.zehn

# ---- engine.tns ----

ENGINE_LINK_OBJS = main_engine.o nspire_renderer.o nspire_asset.o nspire_engine_renderer.o nspire_overlay.o nspire_font5x7.o nspire_file_system.o $(ENGINE_OBJS_FROM_SRC) $(ENGINE_VENDOR_OBJS)

engine.elf: $(ENGINE_LINK_OBJS)
	$(LD) $^ -o $@ $(LDFLAGS) -lsyscalls_nspireio -lnspireio

engine.tns: engine.elf
	$(GENZEHN) --input $^ --output $@.zehn --name "butterscotch-engine"
	make-prg $@.zehn $@
	rm $@.zehn

# ---- clock_test.tns (standalone clock() reliability diagnostic) ----

clock_test.elf: clock_test.o nspire_font5x7.o
	$(LD) $^ -o $@ $(LDFLAGS)

clock_test.tns: clock_test.elf
	$(GENZEHN) --input $^ --output $@.zehn --name "clock-test" --240x320-support true --uses-lcd-blit true
	make-prg $@.zehn $@
	rm $@.zehn

clean:
	rm -rf obj
	rm -f *.o *.elf *.tns *.zehn
