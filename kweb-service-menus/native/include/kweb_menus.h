#ifndef KWEB_MENUS_H_
#define KWEB_MENUS_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_MENUS_ABI_BUILD)
#define KWEB_MENUS_EXPORT __declspec(dllexport)
#else
#define KWEB_MENUS_EXPORT __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define KWEB_MENUS_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_MENUS_EXPORT
#endif

#if defined(_WIN32)
#define KWEB_MENUS_CALL __cdecl
#else
#define KWEB_MENUS_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_MENUS_ABI_VERSION ((uint32_t)1)
#define KWEB_MENUS_MAX_ID 128u
#define KWEB_MENUS_MAX_LABEL 256u
#define KWEB_MENUS_MAX_ERROR 96u
#define KWEB_MENUS_MAX_ITEMS 512u
#define KWEB_MENUS_MAX_DEPTH 8u
#define KWEB_MENUS_MAX_ACCELERATOR_MODIFIERS 3u
#define KWEB_MENUS_MAX_OPEN_POPUPS 4u
#define KWEB_MENUS_EVENT_CAPACITY 64u
#define KWEB_MENUS_MAX_ICON_DIMENSION 256u
#define KWEB_MENUS_MAX_ICON_BYTES 262144u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_menus_status;
#define KWEB_MENUS_STATUS_OK ((kweb_menus_status)0)
#define KWEB_MENUS_STATUS_INVALID_ARGUMENT ((kweb_menus_status)1)
#define KWEB_MENUS_STATUS_ABI_MISMATCH ((kweb_menus_status)2)
#define KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE ((kweb_menus_status)3)
#define KWEB_MENUS_STATUS_TREE_INVALID ((kweb_menus_status)4)
#define KWEB_MENUS_STATUS_TREE_TOO_LARGE ((kweb_menus_status)5)
#define KWEB_MENUS_STATUS_TREE_DEPTH_EXCEEDED ((kweb_menus_status)6)
#define KWEB_MENUS_STATUS_COMMAND_DUPLICATE ((kweb_menus_status)7)
#define KWEB_MENUS_STATUS_ACCELERATOR_INVALID ((kweb_menus_status)8)
#define KWEB_MENUS_STATUS_ACCELERATOR_DUPLICATE ((kweb_menus_status)9)
#define KWEB_MENUS_STATUS_VERSION_STALE ((kweb_menus_status)10)
#define KWEB_MENUS_STATUS_WINDOW_UNKNOWN ((kweb_menus_status)11)
#define KWEB_MENUS_STATUS_PAGE_UNKNOWN ((kweb_menus_status)12)
#define KWEB_MENUS_STATUS_MENU_NOT_DECLARED ((kweb_menus_status)13)
#define KWEB_MENUS_STATUS_POPUP_LIMIT ((kweb_menus_status)14)
#define KWEB_MENUS_STATUS_ANCHOR_INVALID ((kweb_menus_status)15)
#define KWEB_MENUS_STATUS_TARGET_UNSUPPORTED ((kweb_menus_status)16)
#define KWEB_MENUS_STATUS_ICON_UNSUPPORTED ((kweb_menus_status)17)
#define KWEB_MENUS_STATUS_NATIVE_FAILED ((kweb_menus_status)18)
#define KWEB_MENUS_STATUS_NO_EVENT ((kweb_menus_status)19)
#define KWEB_MENUS_STATUS_OWNER_CLOSED ((kweb_menus_status)20)
#define KWEB_MENUS_STATUS_ALREADY_OPEN ((kweb_menus_status)21)

/* Item kinds. */
typedef uint32_t kweb_menus_item_kind;
#define KWEB_MENUS_ITEM_COMMAND ((kweb_menus_item_kind)1)
#define KWEB_MENUS_ITEM_CHECKBOX ((kweb_menus_item_kind)2)
#define KWEB_MENUS_ITEM_RADIO ((kweb_menus_item_kind)3)
#define KWEB_MENUS_ITEM_SEPARATOR ((kweb_menus_item_kind)4)
#define KWEB_MENUS_ITEM_SUBMENU ((kweb_menus_item_kind)5)

/* Item flags. */
#define KWEB_MENUS_ITEM_FLAG_ENABLED ((uint32_t)1u << 0)
#define KWEB_MENUS_ITEM_FLAG_VISIBLE ((uint32_t)1u << 1)
#define KWEB_MENUS_ITEM_FLAG_CHECKED ((uint32_t)1u << 2)
#define KWEB_MENUS_ITEM_FLAG_TEMPLATE_ICON ((uint32_t)1u << 3)

/* Roles. The numeric value is the 1-based ordinal of KWebMenuRole. */
typedef uint32_t kweb_menus_role;
#define KWEB_MENUS_ROLE_NONE ((kweb_menus_role)0)
#define KWEB_MENUS_ROLE_ABOUT ((kweb_menus_role)1)
#define KWEB_MENUS_ROLE_SERVICES ((kweb_menus_role)2)
#define KWEB_MENUS_ROLE_HIDE ((kweb_menus_role)3)
#define KWEB_MENUS_ROLE_HIDE_OTHERS ((kweb_menus_role)4)
#define KWEB_MENUS_ROLE_SHOW_ALL ((kweb_menus_role)5)
#define KWEB_MENUS_ROLE_QUIT ((kweb_menus_role)6)
#define KWEB_MENUS_ROLE_CLOSE ((kweb_menus_role)7)
#define KWEB_MENUS_ROLE_MINIMIZE ((kweb_menus_role)8)
#define KWEB_MENUS_ROLE_ZOOM ((kweb_menus_role)9)
#define KWEB_MENUS_ROLE_TOGGLE_FULL_SCREEN ((kweb_menus_role)10)
#define KWEB_MENUS_ROLE_BRING_ALL_TO_FRONT ((kweb_menus_role)11)
#define KWEB_MENUS_ROLE_WINDOW ((kweb_menus_role)12)
#define KWEB_MENUS_ROLE_HELP ((kweb_menus_role)13)
#define KWEB_MENUS_ROLE_UNDO ((kweb_menus_role)14)
#define KWEB_MENUS_ROLE_REDO ((kweb_menus_role)15)
#define KWEB_MENUS_ROLE_CUT ((kweb_menus_role)16)
#define KWEB_MENUS_ROLE_COPY ((kweb_menus_role)17)
#define KWEB_MENUS_ROLE_PASTE ((kweb_menus_role)18)
#define KWEB_MENUS_ROLE_PASTE_AND_MATCH_STYLE ((kweb_menus_role)19)
#define KWEB_MENUS_ROLE_DELETE ((kweb_menus_role)20)
#define KWEB_MENUS_ROLE_SELECT_ALL ((kweb_menus_role)21)
#define KWEB_MENUS_ROLE_RELOAD ((kweb_menus_role)22)
#define KWEB_MENUS_ROLE_FORCE_RELOAD ((kweb_menus_role)23)
#define KWEB_MENUS_ROLE_TOGGLE_DEV_TOOLS ((kweb_menus_role)24)
#define KWEB_MENUS_ROLE_BACK ((kweb_menus_role)25)
#define KWEB_MENUS_ROLE_FORWARD ((kweb_menus_role)26)

/* Accelerator modifiers and keys. Key values are 1-based KWebMenuKey ordinals. */
#define KWEB_MENUS_MODIFIER_PRIMARY ((uint32_t)1u << 0)
#define KWEB_MENUS_MODIFIER_CONTROL ((uint32_t)1u << 1)
#define KWEB_MENUS_MODIFIER_ALT ((uint32_t)1u << 2)
#define KWEB_MENUS_MODIFIER_SHIFT ((uint32_t)1u << 3)
#define KWEB_MENUS_MODIFIER_META ((uint32_t)1u << 4)

#define KWEB_MENUS_KEY_NONE ((uint32_t)0u)
#define KWEB_MENUS_KEY_A ((uint32_t)1u)
#define KWEB_MENUS_KEY_B ((uint32_t)2u)
#define KWEB_MENUS_KEY_C ((uint32_t)3u)
#define KWEB_MENUS_KEY_D ((uint32_t)4u)
#define KWEB_MENUS_KEY_E ((uint32_t)5u)
#define KWEB_MENUS_KEY_F ((uint32_t)6u)
#define KWEB_MENUS_KEY_G ((uint32_t)7u)
#define KWEB_MENUS_KEY_H ((uint32_t)8u)
#define KWEB_MENUS_KEY_I ((uint32_t)9u)
#define KWEB_MENUS_KEY_J ((uint32_t)10u)
#define KWEB_MENUS_KEY_K ((uint32_t)11u)
#define KWEB_MENUS_KEY_L ((uint32_t)12u)
#define KWEB_MENUS_KEY_M ((uint32_t)13u)
#define KWEB_MENUS_KEY_N ((uint32_t)14u)
#define KWEB_MENUS_KEY_O ((uint32_t)15u)
#define KWEB_MENUS_KEY_P ((uint32_t)16u)
#define KWEB_MENUS_KEY_Q ((uint32_t)17u)
#define KWEB_MENUS_KEY_R ((uint32_t)18u)
#define KWEB_MENUS_KEY_S ((uint32_t)19u)
#define KWEB_MENUS_KEY_T ((uint32_t)20u)
#define KWEB_MENUS_KEY_U ((uint32_t)21u)
#define KWEB_MENUS_KEY_V ((uint32_t)22u)
#define KWEB_MENUS_KEY_W ((uint32_t)23u)
#define KWEB_MENUS_KEY_X ((uint32_t)24u)
#define KWEB_MENUS_KEY_Y ((uint32_t)25u)
#define KWEB_MENUS_KEY_Z ((uint32_t)26u)
#define KWEB_MENUS_KEY_DIGIT_0 ((uint32_t)27u)
#define KWEB_MENUS_KEY_DIGIT_1 ((uint32_t)28u)
#define KWEB_MENUS_KEY_DIGIT_2 ((uint32_t)29u)
#define KWEB_MENUS_KEY_DIGIT_3 ((uint32_t)30u)
#define KWEB_MENUS_KEY_DIGIT_4 ((uint32_t)31u)
#define KWEB_MENUS_KEY_DIGIT_5 ((uint32_t)32u)
#define KWEB_MENUS_KEY_DIGIT_6 ((uint32_t)33u)
#define KWEB_MENUS_KEY_DIGIT_7 ((uint32_t)34u)
#define KWEB_MENUS_KEY_DIGIT_8 ((uint32_t)35u)
#define KWEB_MENUS_KEY_DIGIT_9 ((uint32_t)36u)
#define KWEB_MENUS_KEY_F1 ((uint32_t)37u)
#define KWEB_MENUS_KEY_F2 ((uint32_t)38u)
#define KWEB_MENUS_KEY_F3 ((uint32_t)39u)
#define KWEB_MENUS_KEY_F4 ((uint32_t)40u)
#define KWEB_MENUS_KEY_F5 ((uint32_t)41u)
#define KWEB_MENUS_KEY_F6 ((uint32_t)42u)
#define KWEB_MENUS_KEY_F7 ((uint32_t)43u)
#define KWEB_MENUS_KEY_F8 ((uint32_t)44u)
#define KWEB_MENUS_KEY_F9 ((uint32_t)45u)
#define KWEB_MENUS_KEY_F10 ((uint32_t)46u)
#define KWEB_MENUS_KEY_F11 ((uint32_t)47u)
#define KWEB_MENUS_KEY_F12 ((uint32_t)48u)
#define KWEB_MENUS_KEY_BACKSPACE ((uint32_t)49u)
#define KWEB_MENUS_KEY_DELETE ((uint32_t)50u)
#define KWEB_MENUS_KEY_INSERT ((uint32_t)51u)
#define KWEB_MENUS_KEY_HOME ((uint32_t)52u)
#define KWEB_MENUS_KEY_END ((uint32_t)53u)
#define KWEB_MENUS_KEY_PAGE_UP ((uint32_t)54u)
#define KWEB_MENUS_KEY_PAGE_DOWN ((uint32_t)55u)
#define KWEB_MENUS_KEY_ARROW_LEFT ((uint32_t)56u)
#define KWEB_MENUS_KEY_ARROW_RIGHT ((uint32_t)57u)
#define KWEB_MENUS_KEY_ARROW_UP ((uint32_t)58u)
#define KWEB_MENUS_KEY_ARROW_DOWN ((uint32_t)59u)
#define KWEB_MENUS_KEY_ENTER ((uint32_t)60u)
#define KWEB_MENUS_KEY_ESCAPE ((uint32_t)61u)
#define KWEB_MENUS_KEY_TAB ((uint32_t)62u)
#define KWEB_MENUS_KEY_SPACE ((uint32_t)63u)
#define KWEB_MENUS_KEY_PLUS ((uint32_t)64u)
#define KWEB_MENUS_KEY_MINUS ((uint32_t)65u)
#define KWEB_MENUS_KEY_EQUAL ((uint32_t)66u)
#define KWEB_MENUS_KEY_COMMA ((uint32_t)67u)
#define KWEB_MENUS_KEY_PERIOD ((uint32_t)68u)
#define KWEB_MENUS_KEY_SEMICOLON ((uint32_t)69u)
#define KWEB_MENUS_KEY_SLASH ((uint32_t)70u)
#define KWEB_MENUS_KEY_BACKSLASH ((uint32_t)71u)
#define KWEB_MENUS_KEY_BRACKET_LEFT ((uint32_t)72u)
#define KWEB_MENUS_KEY_BRACKET_RIGHT ((uint32_t)73u)
#define KWEB_MENUS_KEY_QUOTE ((uint32_t)74u)
#define KWEB_MENUS_KEY_BACKQUOTE ((uint32_t)75u)
#define KWEB_MENUS_MAX_KEY ((uint32_t)75u)

/* Owners, popup sources, popup coordinate spaces, outcomes and dismiss reasons. */
typedef uint32_t kweb_menus_owner_kind;
#define KWEB_MENUS_OWNER_APPLICATION ((kweb_menus_owner_kind)1)
#define KWEB_MENUS_OWNER_WINDOW ((kweb_menus_owner_kind)2)
#define KWEB_MENUS_OWNER_PAGE ((kweb_menus_owner_kind)3)

typedef uint32_t kweb_menus_popup_source;
#define KWEB_MENUS_POPUP_SOURCE_HOST ((kweb_menus_popup_source)1)
#define KWEB_MENUS_POPUP_SOURCE_RENDERER ((kweb_menus_popup_source)2)
#define KWEB_MENUS_POPUP_SOURCE_PAGE_CONTEXT ((kweb_menus_popup_source)3)

typedef uint32_t kweb_menus_outcome;
#define KWEB_MENUS_OUTCOME_INVOKED ((kweb_menus_outcome)1)
#define KWEB_MENUS_OUTCOME_DISMISSED ((kweb_menus_outcome)2)
#define KWEB_MENUS_OUTCOME_FAILED ((kweb_menus_outcome)3)

typedef uint32_t kweb_menus_dismiss_reason;
#define KWEB_MENUS_DISMISS_USER ((kweb_menus_dismiss_reason)1)
#define KWEB_MENUS_DISMISS_REPLACED ((kweb_menus_dismiss_reason)2)
#define KWEB_MENUS_DISMISS_OWNER_CLOSED ((kweb_menus_dismiss_reason)3)
#define KWEB_MENUS_DISMISS_CANCELLED ((kweb_menus_dismiss_reason)4)
#define KWEB_MENUS_DISMISS_OWNER_MOVED ((kweb_menus_dismiss_reason)5)
#define KWEB_MENUS_DISMISS_NATIVE ((kweb_menus_dismiss_reason)6)

/* Event kinds and invocation sources. */
typedef uint32_t kweb_menus_event_kind;
#define KWEB_MENUS_EVENT_INVOKED ((kweb_menus_event_kind)1)
#define KWEB_MENUS_EVENT_DISMISSED ((kweb_menus_event_kind)2)
#define KWEB_MENUS_EVENT_FAILED ((kweb_menus_event_kind)3)

typedef uint32_t kweb_menus_invocation_source;
#define KWEB_MENUS_SOURCE_APPLICATION_MENU ((kweb_menus_invocation_source)1)
#define KWEB_MENUS_SOURCE_WINDOW_MENU ((kweb_menus_invocation_source)2)
#define KWEB_MENUS_SOURCE_POPUP ((kweb_menus_invocation_source)3)
#define KWEB_MENUS_SOURCE_PAGE_MENU ((kweb_menus_invocation_source)4)

/* Provider capability flags. */
#define KWEB_MENUS_CAP_APPLICATION_MENU ((uint32_t)1u << 0)
#define KWEB_MENUS_CAP_WINDOW_MENU ((uint32_t)1u << 1)
#define KWEB_MENUS_CAP_PAGE_MENU ((uint32_t)1u << 2)
#define KWEB_MENUS_CAP_GLOBAL_MENU_HOST ((uint32_t)1u << 3)
#define KWEB_MENUS_CAP_SUBMENUS ((uint32_t)1u << 4)
#define KWEB_MENUS_CAP_CHECKBOX_ITEMS ((uint32_t)1u << 5)
#define KWEB_MENUS_CAP_RADIO_ITEMS ((uint32_t)1u << 6)
#define KWEB_MENUS_CAP_ITEM_ICONS ((uint32_t)1u << 7)
#define KWEB_MENUS_CAP_MNEMONICS ((uint32_t)1u << 8)
#define KWEB_MENUS_CAP_ACCELERATOR_DISPLAY ((uint32_t)1u << 9)
#define KWEB_MENUS_CAP_ACCELERATOR_ACTIVATION ((uint32_t)1u << 10)
#define KWEB_MENUS_CAP_POPUP_POSITIONING ((uint32_t)1u << 11)

typedef struct kweb_menus_string {
  const uint8_t *data;
  size_t size;
} kweb_menus_string;

typedef struct kweb_menus_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_menus_string application_id;
  kweb_menus_string package_identity;
  uint64_t reserved;
} kweb_menus_configuration;

/* One flat preorder node. Submenu children follow their parent contiguously. */
typedef struct kweb_menus_item {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_menus_item_kind kind;
  uint32_t flags;
  kweb_menus_role role;
  uint32_t mnemonic;
  uint32_t accelerator_modifiers;
  uint32_t accelerator_key;
  kweb_menus_string id;
  kweb_menus_string label;
  kweb_menus_string icon_id;
  kweb_menus_string icon_sha256;
  kweb_menus_string icon_pixels;
  uint32_t icon_width;
  uint32_t icon_height;
  uint32_t submenu_count;
  uint32_t reserved;
} kweb_menus_item;

typedef struct kweb_menus_tree {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t version;
  uint32_t item_count;
  uint32_t reserved;
  kweb_menus_string menu_id;
  const kweb_menus_item *items;
} kweb_menus_tree;

/* Popup anchors are provider screen coordinates; the JVM service converts the
 * owner-relative request position before dispatch. */
typedef struct kweb_menus_popup_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_menus_string menu_id;
  kweb_menus_owner_kind owner_kind;
  kweb_menus_popup_source source;
  kweb_menus_string owner_id;
  int32_t screen_x;
  int32_t screen_y;
  uint64_t native_window;
} kweb_menus_popup_request;

typedef struct kweb_menus_popup_result {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_menus_outcome outcome;
  kweb_menus_dismiss_reason dismiss_reason;
  uint64_t tree_version;
  char popup_id[KWEB_MENUS_MAX_ID + 1u];
  char command_id[KWEB_MENUS_MAX_ID + 1u];
  char code[KWEB_MENUS_MAX_ERROR];
} kweb_menus_popup_result;

typedef struct kweb_menus_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t flags;
  uint32_t reserved;
  uint64_t native_roles;
} kweb_menus_capabilities_result;

typedef struct kweb_menus_event {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_menus_event_kind kind;
  kweb_menus_dismiss_reason dismiss_reason;
  kweb_menus_owner_kind owner_kind;
  kweb_menus_invocation_source source;
  uint64_t sequence;
  uint64_t tree_version;
  char owner_id[KWEB_MENUS_MAX_ID + 1u];
  char popup_id[KWEB_MENUS_MAX_ID + 1u];
  char command_id[KWEB_MENUS_MAX_ID + 1u];
  char code[KWEB_MENUS_MAX_ERROR];
} kweb_menus_event;

/* Introspection for the FFM binding: the byte size of each published struct. */
#define KWEB_MENUS_STRUCT_CONFIGURATION ((uint32_t)1u)
#define KWEB_MENUS_STRUCT_ITEM ((uint32_t)2u)
#define KWEB_MENUS_STRUCT_TREE ((uint32_t)3u)
#define KWEB_MENUS_STRUCT_POPUP_REQUEST ((uint32_t)4u)
#define KWEB_MENUS_STRUCT_POPUP_RESULT ((uint32_t)5u)
#define KWEB_MENUS_STRUCT_CAPABILITIES ((uint32_t)6u)
#define KWEB_MENUS_STRUCT_EVENT ((uint32_t)7u)

KWEB_MENUS_EXPORT uint32_t KWEB_MENUS_CALL kweb_menus_abi_version(void);

KWEB_MENUS_EXPORT uint32_t KWEB_MENUS_CALL kweb_menus_struct_size(uint32_t which);

KWEB_MENUS_EXPORT const char * KWEB_MENUS_CALL kweb_menus_status_name(kweb_menus_status status);

KWEB_MENUS_EXPORT const char * KWEB_MENUS_CALL kweb_menus_provider_id(void);

KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_open(const kweb_menus_configuration *configuration, uint64_t *handle);

KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_capabilities(uint64_t handle, kweb_menus_capabilities_result *result);

/* `tree` is null to clear the owner's menu. */
KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_set_application_menu(uint64_t handle, const kweb_menus_tree *tree);

KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_set_window_menu(uint64_t handle, kweb_menus_string window_id, uint64_t native_window,
                           const kweb_menus_tree *tree);

KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_declare_page_menu(uint64_t handle, kweb_menus_string page_token, const kweb_menus_tree *tree);

KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_clear_page_menu(uint64_t handle, kweb_menus_string page_token, kweb_menus_string menu_id);

/* Presents the popup and returns after it closed with the terminal outcome. */
KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_show_popup(uint64_t handle, const kweb_menus_popup_request *request,
                      kweb_menus_popup_result *result);

KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL
kweb_menus_poll_event(uint64_t handle, kweb_menus_event *event);

KWEB_MENUS_EXPORT kweb_menus_status KWEB_MENUS_CALL kweb_menus_close(uint64_t handle);

KWEB_MENUS_EXPORT uint32_t KWEB_MENUS_CALL kweb_menus_live_count(void);

#ifdef __cplusplus
}
#endif

#endif
