package com.mrpowergamerbr.butterscotchpreprocessor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr

@Composable
fun StringSetTable(title: String, type: String, set: SnapshotStateSet<String>) {
    FieldWrapper {
        FieldInformation {
            FieldLabel(title)
        }

        Table(attrs = {
            classes("fancy-table")
        }) {
            Thead {
                Tr {
                    Th { Text(type) }
                    Th {}
                }
            }
            Tbody {
                for (string in set) {
                    Tr {
                        Td { Text(string) }
                        Td(attrs = {
                            classes("action-cell")
                        }) {
                            DiscordButton(
                                DiscordButtonType.NO_BACKGROUND_THEME_DEPENDENT_DARK_TEXT,
                                attrs = {
                                    onClick {
                                        set.remove(string)
                                    }
                                }
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }

        var newString by remember { mutableStateOf<String>("") }

        Div(attrs = {
            classes("add-mapping-form")
        }) {
            TextInput(newString) {
                attr("placeholder", type)
                onInput {
                    newString = it.value
                }
            }

            DiscordButton(
                DiscordButtonType.PRIMARY,
                {
                    onClick {
                        if (newString.isNotBlank()) {
                            set.add(newString)
                            newString = ""
                        }
                    }
                }
            ) {
                Text("Add")
            }
        }
    }
}