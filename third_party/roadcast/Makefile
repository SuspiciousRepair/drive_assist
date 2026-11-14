CC ?= cc
AR ?= ar
PKG_CONFIG ?= pkg-config

BUILD_DIR := build
COMMON_SOURCES := src/protocol.c
GENERATED_HEADERS := include/roadcast_catalog_generated.h include/roadcast_frames.h
COMMON_HEADERS := $(sort $(wildcard include/*.h) $(GENERATED_HEADERS))
CATALOG_SOURCES := src/catalog.c src/catalog_generated.c
SOURCE_SOURCES := src/vhal_source.c src/source_session.c
CATALOG_GENERATED := include/roadcast_catalog_generated.h include/roadcast_frames.h src/catalog_generated.c
LIBUV_CFLAGS := $(shell $(PKG_CONFIG) --cflags libuv 2>/dev/null)
LIBUV_LIBS := $(shell $(PKG_CONFIG) --libs libuv 2>/dev/null)
LIBELF_PREFIX := $(shell brew --prefix libelf 2>/dev/null)
LIBELF_CFLAGS := $(if $(LIBELF_PREFIX),-I$(LIBELF_PREFIX)/include)

CPPFLAGS := -Iinclude
CFLAGS ?= -O2 -g
CFLAGS += -std=c11 -Wall -Wextra -Wpedantic -Werror
LDFLAGS ?=

.PHONY: all android clean generate test integration

all: $(BUILD_DIR)/roadcastd $(BUILD_DIR)/roadcastctl \
	$(BUILD_DIR)/libroadcast_client.a $(BUILD_DIR)/roadcast_client_smoke

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/.catalog.stamp: data/dbc.json scripts/generate-catalog.py | $(BUILD_DIR)
	scripts/generate-catalog.py
	@touch $@

$(CATALOG_GENERATED): $(BUILD_DIR)/.catalog.stamp
	@if [ ! -f $@ ]; then \
		rm -f $(BUILD_DIR)/.catalog.stamp; \
		$(MAKE) $(BUILD_DIR)/.catalog.stamp; \
	fi

generate: | $(BUILD_DIR)
	scripts/generate-catalog.py
	@touch $(BUILD_DIR)/.catalog.stamp

$(BUILD_DIR)/roadcastd: src/roadcastd.c $(SOURCE_SOURCES) $(COMMON_SOURCES) $(CATALOG_SOURCES) $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(LIBUV_CFLAGS) $(LIBELF_CFLAGS) $(CFLAGS) \
		$(LDFLAGS) -o $@ src/roadcastd.c $(SOURCE_SOURCES) \
		$(COMMON_SOURCES) $(CATALOG_SOURCES) $(LIBUV_LIBS) -pthread

$(BUILD_DIR)/roadcastctl: src/roadcastctl.c $(COMMON_SOURCES) $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ \
		src/roadcastctl.c $(COMMON_SOURCES)

$(BUILD_DIR)/client.o: src/client.c $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c -o $@ src/client.c

$(BUILD_DIR)/client_protocol.o: src/protocol.c $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c -o $@ src/protocol.c

$(BUILD_DIR)/libroadcast_client.a: $(BUILD_DIR)/client.o $(BUILD_DIR)/client_protocol.o
	$(AR) rcs $@ $^

$(BUILD_DIR)/roadcast_client_smoke: src/roadcast_client_smoke.c $(BUILD_DIR)/libroadcast_client.a $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ \
		src/roadcast_client_smoke.c $(BUILD_DIR)/libroadcast_client.a -pthread

$(BUILD_DIR)/test_protocol: tests/test_protocol.c $(COMMON_SOURCES) $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ \
		tests/test_protocol.c $(COMMON_SOURCES)

$(BUILD_DIR)/test_catalog: tests/test_catalog.c $(COMMON_SOURCES) $(CATALOG_SOURCES) $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ \
		tests/test_catalog.c $(COMMON_SOURCES) $(CATALOG_SOURCES)

$(BUILD_DIR)/test_vhal_elf: tests/test_vhal_elf.c src/vhal_source.c $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(LIBELF_CFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ \
		tests/test_vhal_elf.c src/vhal_source.c

$(BUILD_DIR)/test_client_deadlines: tests/test_client_deadlines.c $(BUILD_DIR)/libroadcast_client.a $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ \
		tests/test_client_deadlines.c $(BUILD_DIR)/libroadcast_client.a -pthread

$(BUILD_DIR)/idle_clients: tests/idle_clients.c | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ tests/idle_clients.c

$(BUILD_DIR)/test_distribution: tests/test_distribution.c src/roadcastd.c $(SOURCE_SOURCES) $(COMMON_SOURCES) $(CATALOG_SOURCES) $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(LIBUV_CFLAGS) $(LIBELF_CFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ \
		tests/test_distribution.c $(SOURCE_SOURCES) $(COMMON_SOURCES) $(CATALOG_SOURCES) $(LIBUV_LIBS) -pthread

$(BUILD_DIR)/test_source_session: tests/test_source_session.c src/source_session.c $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ tests/test_source_session.c

$(BUILD_DIR)/test_vhal_source: tests/test_vhal_source.c src/vhal_source.c $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(LIBELF_CFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ tests/test_vhal_source.c

$(BUILD_DIR)/source_recovery_watch: tests/source_recovery_watch.c $(BUILD_DIR)/libroadcast_client.a $(COMMON_HEADERS) | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) -o $@ tests/source_recovery_watch.c $(BUILD_DIR)/libroadcast_client.a -pthread

test: $(BUILD_DIR)/test_protocol $(BUILD_DIR)/test_catalog $(BUILD_DIR)/test_vhal_elf $(BUILD_DIR)/test_client_deadlines $(BUILD_DIR)/test_distribution $(BUILD_DIR)/test_source_session $(BUILD_DIR)/test_vhal_source
	$(BUILD_DIR)/test_protocol
	$(BUILD_DIR)/test_catalog
	$(BUILD_DIR)/test_vhal_elf
	$(BUILD_DIR)/test_client_deadlines
	$(BUILD_DIR)/test_distribution
	$(BUILD_DIR)/test_source_session
	$(BUILD_DIR)/test_vhal_source

integration: all $(BUILD_DIR)/idle_clients $(BUILD_DIR)/test_vhal_source $(BUILD_DIR)/source_recovery_watch
	tests/catchup_integration.sh
	tests/integration.sh
	bash tests/dense_integration.sh
	sh tests/recovery_integration.sh

android:
	scripts/build-android.sh

clean:
	rm -rf $(BUILD_DIR)
