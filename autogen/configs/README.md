# Emulator Configuration Templates

This directory contains configuration templates for different LibRetro emulator cores used by the autogen action.

## Available Emulators

- **parallel_n64.xml** - Nintendo 64 (parallel_n64)
  - Touchscreen-first configuration
  - Analog stick enabled
  - All C-buttons visible
  - Force touch gamepad: `true`

- **mgba.xml** - Game Boy / Game Boy Advance
  - Standard 2-button layout (A/B)
  - No analog stick
  - Force touch gamepad: `false`

- **snesx9.xml** - Super Nintendo Entertainment System
  - 4-button layout (A/B/X/Y)
  - Standard shoulder buttons (L1/R1)
  - Force touch gamepad: `false`

- **genesis_plus_gx.xml** - Genesis / Mega Drive
  - 3/6-button layout
  - Includes core variables for controller configuration
  - Force touch gamepad: `false`

## Usage

When using the autogen action, provide one of these configuration files (or customize them based on your needs) in your payload. The autogen workflow will merge/replace the app's `config.xml` with your payload configuration.

## Customization

Feel free to modify these templates for specific games:
- Update `config_name` for the ROM title
- Add `config_variables` for core-specific options
- Adjust button visibility (`config_gamepad_*` flags) as needed
- Change `config_load_bytes` for memory management
