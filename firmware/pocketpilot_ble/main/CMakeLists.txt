idf_component_register(SRCS "pocketpilot_ble_main.c"
                       "pp_button.c"
                       "pp_audio.c"
                       "pp_asr.c"
                       INCLUDE_DIRS "."
                       REQUIRES bt nvs_flash driver esp_adc esp-sr)