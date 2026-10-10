#ifndef KWEB_TRAY_H_
#define KWEB_TRAY_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_TRAY_ABI_BUILD)
#define KWEB_TRAY_EXPORT __declspec(dllexport)
#else
#define KWEB_TRAY_EXPORT __declspec(dllimport)
#endif
#else
#define KWEB_TRAY_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_TRAY_CALL __cdecl
#else
#define KWEB_TRAY_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_TRAY_ABI_VERSION ((uint32_t)1)
#define KWEB_TRAY_MAX_ID 128u
#define KWEB_TRAY_MAX_RESOURCE 128u
#define KWEB_TRAY_MAX_TOOLTIP 64u
#define KWEB_TRAY_MAX_ERROR 96u
#define KWEB_TRAY_MAX_ITEMS 4u
#define KWEB_TRAY_MAX_ICON_VARIANTS 8u
#define KWEB_TRAY_MAX_ICON_SCALE 8u
#define KWEB_TRAY_MAX_TREE_ITEMS 512u
#define KWEB_TRAY_MAX_LABEL 256u
#define KWEB_TRAY_MAX_ICON_DIMENSION 256u
#define KWEB_TRAY_MAX_ICON_BYTES 262144u
#define KWEB_TRAY_EVENT_CAPACITY 64u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_tray_status;
#define KWEB_TRAY_STATUS_OK ((kweb_tray_status)0)
#define KWEB_TRAY_STATUS_INVALID_ARGUMENT ((kweb_tray_status)1)
#define KWEB_TRAY_STATUS_ABI_MISMATCH ((kweb_tray_status)2)
#define KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE ((kweb_tray_status)3)
#define KWEB_TRAY_STATUS_ITEM_EXISTS ((kweb_tray_status)4)
#define KWEB_TRAY_STATUS_ITEM_UNKNOWN ((kweb_tray_status)5)
#define KWEB_TRAY_STATUS_ITEM_LIMIT ((kweb_tray_status)6)
#define KWEB_TRAY_STATUS_ICON_INVALID ((kweb_tray_status)7)
#define KWEB_TRAY_STATUS_ICON_UNSUPPORTED ((kweb_tray_status)8)
#define KWEB_TRAY_STATUS_TOOLTIP_INVALID ((kweb_tray_status)9)
#define KWEB_TRAY_STATUS_TOOLTIP_UNSUPPORTED ((kweb_tray_status)10)
#define KWEB_TRAY_STATUS_ACTIVATION_UNSUPPORTED ((kweb_tray_status)11)
#define KWEB_TRAY_STATUS_MENU_INVALID ((kweb_tray_status)12)
#define KWEB_TRAY_STATUS_MENU_UNSUPPORTED ((kweb_tray_status)13)
#define KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE ((kweb_tray_status)14)
#define KWEB_TRAY_STATUS_NATIVE_FAILED ((kweb_tray_status)15)
#define KWEB_TRAY_STATUS_NO_EVENT ((kweb_tray_status)16)
#define KWEB_TRAY_STATUS_OWNER_CLOSED ((kweb_tray_status)17)
#define KWEB_TRAY_STATUS_ALREADY_OPEN ((kweb_tray_status)18)

/* Activation bits. */
#define KWEB_TRAY_ACTIVATION_PRIMARY ((uint32_t)1u << 0)
#define KWEB_TRAY_ACTIVATION_SECONDARY ((uint32_t)1u << 1)
#define KWEB_TRAY_ACTIVATION_DOUBLE ((uint32_t)1u << 2)

/* Menu item kinds and flags (the tray menu is an RFC 0017 tree subset). */
typedef uint32_t kweb_tray_menu_kind;
#define KWEB_TRAY_MENU_COMMAND ((kweb_tray_menu_kind)1)
#define KWEB_TRAY_MENU_CHECKBOX ((kweb_tray_menu_kind)2)
#define KWEB_TRAY_MENU_RADIO ((kweb_tray_menu_kind)3)
#define KWEB_TRAY_MENU_SEPARATOR ((kweb_tray_menu_kind)4)
#define KWEB_TRAY_MENU_SUBMENU ((kweb_tray_menu_kind)5)
#define KWEB_TRAY_MENU_FLAG_ENABLED ((uint32_t)1u << 0)
#define KWEB_TRAY_MENU_FLAG_VISIBLE ((uint32_t)1u << 1)
#define KWEB_TRAY_MENU_FLAG_CHECKED ((uint32_t)1u << 2)

/* Provider capability flags. */
#define KWEB_TRAY_CAP_MENU ((uint32_t)1u << 0)
#define KWEB_TRAY_CAP_TOOLTIP ((uint32_t)1u << 1)
#define KWEB_TRAY_CAP_TEMPLATE_ICON ((uint32_t)1u << 2)
#define KWEB_TRAY_CAP_ICON_VARIANTS ((uint32_t)1u << 3)
#define KWEB_TRAY_CAP_BALLOON ((uint32_t)1u << 4)
#define KWEB_TRAY_CAP_BOUNDS ((uint32_t)1u << 5)
#define KWEB_TRAY_CAP_EXPLORER_RESTART_RECOVERY ((uint32_t)1u << 6)
#define KWEB_TRAY_CAP_WATCHER_RECONNECT ((uint32_t)1u << 7)

/* Event kinds and removal reasons. */
typedef uint32_t kweb_tray_event_kind;
#define KWEB_TRAY_EVENT_ACTIVATED ((kweb_tray_event_kind)1)
#define KWEB_TRAY_EVENT_MENU_COMMAND ((kweb_tray_event_kind)2)
#define KWEB_TRAY_EVENT_BALLOON ((kweb_tray_event_kind)3)
#define KWEB_TRAY_EVENT_REMOVED ((kweb_tray_event_kind)4)
#define KWEB_TRAY_EVENT_FAILED ((kweb_tray_event_kind)5)

typedef uint32_t kweb_tray_removal_reason;
#define KWEB_TRAY_REMOVAL_CLOSED ((kweb_tray_removal_reason)1)
#define KWEB_TRAY_REMOVAL_HOST_LOST ((kweb_tray_removal_reason)2)
#define KWEB_TRAY_REMOVAL_PLATFORM_REMOVED ((kweb_tray_removal_reason)3)
#define KWEB_TRAY_REMOVAL_OWNER_CLOSED ((kweb_tray_removal_reason)4)

typedef struct kweb_tray_string {
  const uint8_t *data;
  size_t size;
} kweb_tray_string;

typedef struct kweb_tray_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_tray_string application_id;
  kweb_tray_string package_identity;
  uint64_t reserved;
} kweb_tray_configuration;

/* One icon variant: straight RGBA8 pixels from a verified package resource. */
typedef struct kweb_tray_icon_variant {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t scale;
  uint32_t template_icon;
  kweb_tray_string resource_id;
  kweb_tray_string sha256;
  kweb_tray_string pixels;
  uint32_t width;
  uint32_t height;
} kweb_tray_icon_variant;

typedef struct kweb_tray_item_spec {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t activation_bits;
  uint32_t icon_variant_count;
  kweb_tray_string id;
  kweb_tray_string tooltip;
  kweb_tray_icon_variant icon_variants[KWEB_TRAY_MAX_ICON_VARIANTS];
} kweb_tray_item_spec;

/* One flat preorder menu node; submenu children follow their parent. */
typedef struct kweb_tray_menu_item {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_tray_menu_kind kind;
  uint32_t flags;
  uint32_t submenu_count;
  uint32_t reserved;
  kweb_tray_string id;
  kweb_tray_string label;
} kweb_tray_menu_item;

typedef struct kweb_tray_menu_tree {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t version;
  uint32_t item_count;
  uint32_t reserved;
  kweb_tray_string menu_id;
  const kweb_tray_menu_item *items;
} kweb_tray_menu_tree;

typedef struct kweb_tray_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t flags;
  uint32_t activation_bits;
  char provider_id[KWEB_TRAY_MAX_ID];
} kweb_tray_capabilities_result;

typedef struct kweb_tray_bounds_result {
  uint32_t struct_size;
  uint32_t abi_version;
  int32_t x;
  int32_t y;
  int32_t width;
  int32_t height;
} kweb_tray_bounds_result;

typedef struct kweb_tray_event {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_tray_event_kind kind;
  kweb_tray_removal_reason removal_reason;
  uint32_t activation;
  uint32_t reserved;
  uint64_t sequence;
  uint64_t tree_version;
  char item_id[KWEB_TRAY_MAX_ID + 1u];
  char menu_id[KWEB_TRAY_MAX_ID + 1u];
  char command_id[KWEB_TRAY_MAX_ID + 1u];
  char code[KWEB_TRAY_MAX_ERROR];
} kweb_tray_event;

/* Introspection for the FFM binding: the byte size of each published struct. */
#define KWEB_TRAY_STRUCT_CONFIGURATION ((uint32_t)1u)
#define KWEB_TRAY_STRUCT_ICON_VARIANT ((uint32_t)2u)
#define KWEB_TRAY_STRUCT_ITEM_SPEC ((uint32_t)3u)
#define KWEB_TRAY_STRUCT_MENU_ITEM ((uint32_t)4u)
#define KWEB_TRAY_STRUCT_MENU_TREE ((uint32_t)5u)
#define KWEB_TRAY_STRUCT_CAPABILITIES ((uint32_t)6u)
#define KWEB_TRAY_STRUCT_BOUNDS ((uint32_t)7u)
#define KWEB_TRAY_STRUCT_EVENT ((uint32_t)8u)

KWEB_TRAY_EXPORT uint32_t KWEB_TRAY_CALL kweb_tray_abi_version(void);

KWEB_TRAY_EXPORT uint32_t KWEB_TRAY_CALL kweb_tray_struct_size(uint32_t which);

KWEB_TRAY_EXPORT const char *KWEB_TRAY_CALL kweb_tray_status_name(kweb_tray_status status);

KWEB_TRAY_EXPORT const char *KWEB_TRAY_CALL kweb_tray_provider_id(void);

KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL
kweb_tray_open(const kweb_tray_configuration *configuration, uint64_t *handle);

KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL
kweb_tray_capabilities(uint64_t handle, kweb_tray_capabilities_result *result);

/* Creates or updates one item atomically. */
KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL
kweb_tray_set_item(uint64_t handle, const kweb_tray_item_spec *spec, uint32_t *created_out);

KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL
kweb_tray_close_item(uint64_t handle, kweb_tray_string item_id);

/* `tree` is null to clear the item's menu. */
KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL
kweb_tray_set_menu(uint64_t handle, kweb_tray_string item_id, const kweb_tray_menu_tree *tree);

KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL
kweb_tray_bounds(uint64_t handle, kweb_tray_string item_id, kweb_tray_bounds_result *result);

KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL
kweb_tray_poll_event(uint64_t handle, kweb_tray_event *event);

KWEB_TRAY_EXPORT kweb_tray_status KWEB_TRAY_CALL kweb_tray_close(uint64_t handle);

KWEB_TRAY_EXPORT uint32_t KWEB_TRAY_CALL kweb_tray_live_count(void);

#ifdef __cplusplus
}
#endif

#endif
