package com.mrpowergamerbr.butterscotchpreprocessor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.mrpowergamerbr.butterscotchpreprocessor.GMLKey
import com.mrpowergamerbr.butterscotchpreprocessor.PS2PadKey
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr

@Composable
fun ControllerMappings(title: String, controllerMappings: SnapshotStateMap<PS2PadKey, GMLKey>) {
    FieldWrapper {
        FieldInformation {
            FieldLabel(title)
        }

        Table(attrs = {
            classes("fancy-table")
        }) {
            Thead {
                Tr {
                    Th { Text("PlayStation 2 Key") }
                    Th { Text("GameMaker Key") }
                }
            }
            Tbody {
                for (ps2Key in PS2PadKey.entries) {
                    Tr {
                        Td {
                            Text(
                                when (ps2Key) {
                                    PS2PadKey.PAD_LEFT -> "D-Pad Left"
                                    PS2PadKey.PAD_DOWN -> "D-Pad Down"
                                    PS2PadKey.PAD_RIGHT -> "D-Pad Right"
                                    PS2PadKey.PAD_UP -> "D-Pad Up"
                                    PS2PadKey.PAD_START -> "Start"
                                    PS2PadKey.PAD_R3 -> "R3"
                                    PS2PadKey.PAD_L3 -> "L3"
                                    PS2PadKey.PAD_SELECT -> "Select"
                                    PS2PadKey.PAD_SQUARE -> "Square (□)"
                                    PS2PadKey.PAD_CROSS -> "Cross (X)"
                                    PS2PadKey.PAD_CIRCLE -> "Circle (O)"
                                    PS2PadKey.PAD_TRIANGLE -> "Triangle (△)"
                                    PS2PadKey.PAD_R1 -> "R1"
                                    PS2PadKey.PAD_L1 -> "L1"
                                    PS2PadKey.PAD_R2 -> "R2"
                                    PS2PadKey.PAD_L2 -> "L2"
                                }
                            )
                        }

                        Td {
                            val currentMapping = controllerMappings[ps2Key]

                            key(currentMapping) {
                                Select(attrs = {
                                    onChange { event ->
                                        val selectedValue = event.value
                                        if (selectedValue == "") {
                                            controllerMappings.remove(ps2Key)
                                        } else {
                                            val gmlKey = GMLKey.fromValue(selectedValue!!.toInt())
                                            if (gmlKey != null) {
                                                controllerMappings[ps2Key] = gmlKey
                                            }
                                        }
                                    }
                                }) {
                                    Option("", attrs = {
                                        if (currentMapping == null) selected()
                                    }) {
                                        Text("<not mapped>")
                                    }

                                    for (gmlKey in GMLKey.entries) {
                                        if (gmlKey == GMLKey.VK_NOKEY)
                                            continue

                                        Option(gmlKey.value.toString(), attrs = {
                                            if (currentMapping == gmlKey) selected()
                                        }) {
                                            Text(
                                                when (gmlKey) {
                                                    GMLKey.VK_NOKEY -> "No Key"
                                                    GMLKey.VK_BACKSPACE -> "Backspace"
                                                    GMLKey.VK_TAB -> "Tab"
                                                    GMLKey.VK_ENTER -> "Enter"
                                                    GMLKey.VK_SHIFT -> "Shift"
                                                    GMLKey.VK_CONTROL -> "Control"
                                                    GMLKey.VK_ALT -> "Alt"
                                                    GMLKey.VK_ESCAPE -> "Escape"
                                                    GMLKey.VK_SPACE -> "Space"
                                                    GMLKey.VK_PAGEUP -> "Page Up"
                                                    GMLKey.VK_PAGEDOWN -> "Page Down"
                                                    GMLKey.VK_END -> "End"
                                                    GMLKey.VK_HOME -> "Home"
                                                    GMLKey.VK_LEFT -> "Left Arrow"
                                                    GMLKey.VK_UP -> "Up Arrow"
                                                    GMLKey.VK_RIGHT -> "Right Arrow"
                                                    GMLKey.VK_DOWN -> "Down Arrow"
                                                    GMLKey.VK_INSERT -> "Insert"
                                                    GMLKey.VK_DELETE -> "Delete"
                                                    GMLKey.KEY_0 -> "0"
                                                    GMLKey.KEY_1 -> "1"
                                                    GMLKey.KEY_2 -> "2"
                                                    GMLKey.KEY_3 -> "3"
                                                    GMLKey.KEY_4 -> "4"
                                                    GMLKey.KEY_5 -> "5"
                                                    GMLKey.KEY_6 -> "6"
                                                    GMLKey.KEY_7 -> "7"
                                                    GMLKey.KEY_8 -> "8"
                                                    GMLKey.KEY_9 -> "9"
                                                    GMLKey.KEY_A -> "A"
                                                    GMLKey.KEY_B -> "B"
                                                    GMLKey.KEY_C -> "C"
                                                    GMLKey.KEY_D -> "D"
                                                    GMLKey.KEY_E -> "E"
                                                    GMLKey.KEY_F -> "F"
                                                    GMLKey.KEY_G -> "G"
                                                    GMLKey.KEY_H -> "H"
                                                    GMLKey.KEY_I -> "I"
                                                    GMLKey.KEY_J -> "J"
                                                    GMLKey.KEY_K -> "K"
                                                    GMLKey.KEY_L -> "L"
                                                    GMLKey.KEY_M -> "M"
                                                    GMLKey.KEY_N -> "N"
                                                    GMLKey.KEY_O -> "O"
                                                    GMLKey.KEY_P -> "P"
                                                    GMLKey.KEY_Q -> "Q"
                                                    GMLKey.KEY_R -> "R"
                                                    GMLKey.KEY_S -> "S"
                                                    GMLKey.KEY_T -> "T"
                                                    GMLKey.KEY_U -> "U"
                                                    GMLKey.KEY_V -> "V"
                                                    GMLKey.KEY_W -> "W"
                                                    GMLKey.KEY_X -> "X"
                                                    GMLKey.KEY_Y -> "Y"
                                                    GMLKey.KEY_Z -> "Z"
                                                    GMLKey.VK_F1 -> "F1"
                                                    GMLKey.VK_F2 -> "F2"
                                                    GMLKey.VK_F3 -> "F3"
                                                    GMLKey.VK_F4 -> "F4"
                                                    GMLKey.VK_F5 -> "F5"
                                                    GMLKey.VK_F6 -> "F6"
                                                    GMLKey.VK_F7 -> "F7"
                                                    GMLKey.VK_F8 -> "F8"
                                                    GMLKey.VK_F9 -> "F9"
                                                    GMLKey.VK_F10 -> "F10"
                                                    GMLKey.VK_F11 -> "F11"
                                                    GMLKey.VK_F12 -> "F12"
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}