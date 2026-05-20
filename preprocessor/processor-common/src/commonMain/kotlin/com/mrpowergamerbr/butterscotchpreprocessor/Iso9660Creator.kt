package com.mrpowergamerbr.butterscotchpreprocessor

/**
 * Creates ISO 9660 images.
 *
 * File names may contain "/" to place files in subdirectories (e.g. "LANG/LANG_EN.JSON").
 *
 * Equivalent to: mkisofs -o output.iso -V volumeId -sysid systemId -l -allow-lowercase files...
 */
class Iso9660Creator(
    private val volumeId: String = "UNDERTALE",
    private val systemId: String = "PLAYSTATION"
) {
    companion object {
        private const val SECTOR_SIZE = 2048
        private const val SYSTEM_AREA_SECTORS = 16
    }

    class IsoFile(val name: String, val data: ByteArray)

    private class DirInfo(
        val name: String,       // Directory name ("" for root)
        val parentIndex: Int,   // Index in the directories list (0 = root, root's parent is itself)
        val entries: MutableList<DirEntryInfo> = mutableListOf(),
        var sector: Int = 0,
        var sizeInBytes: Int = 0
    ) {
        val sectorCount get() = (sizeInBytes + SECTOR_SIZE - 1) / SECTOR_SIZE
    }

    private sealed class DirEntryInfo(val name: String) {
        class File(name: String, val isoFile: IsoFile, var sector: Int = 0, var sectorCount: Int = 0) : DirEntryInfo(name)
        class Subdirectory(name: String, val dirIndex: Int) : DirEntryInfo(name)
    }

    fun create(files: List<IsoFile>): ByteArray {
        // Layout:
        // Sectors 0-15:  System Area (zeros)
        // Sector 16:     Primary Volume Descriptor
        // Sector 17:     Volume Descriptor Set Terminator
        // Sector 18:     L Path Table (Little Endian)
        // Sector 19:     M Path Table (Big Endian)
        // Sector 20+:    Directory records (root + subdirectories)
        // After dirs:    File data

        // ---- Phase 1: Build directory tree from file paths ----
        val directories = mutableListOf<DirInfo>()
        val dirsByPath = mutableMapOf<String, Int>()

        // Root directory (index 0, parent is itself)
        directories.add(DirInfo("", 0))
        dirsByPath[""] = 0

        for (file in files) {
            val slashIndex = file.name.lastIndexOf('/')
            val dirPath: String
            val fileName: String
            if (slashIndex >= 0) {
                dirPath = file.name.substring(0, slashIndex)
                fileName = file.name.substring(slashIndex + 1)
            } else {
                dirPath = ""
                fileName = file.name
            }

            // Create any missing parent directories
            if (dirPath.isNotEmpty() && !dirsByPath.containsKey(dirPath)) {
                val parts = dirPath.split("/")
                var currentPath = ""
                for (part in parts) {
                    val parentPath = currentPath
                    currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                    if (!dirsByPath.containsKey(currentPath)) {
                        val parentIndex = dirsByPath[parentPath]!!
                        val newIndex = directories.size
                        directories.add(DirInfo(part, parentIndex))
                        dirsByPath[currentPath] = newIndex
                        directories[parentIndex].entries.add(DirEntryInfo.Subdirectory(part, newIndex))
                    }
                }
            }

            // Add file entry to its parent directory
            val dirIndex = dirsByPath[dirPath]!!
            directories[dirIndex].entries.add(DirEntryInfo.File(fileName, file))
        }

        // ---- Phase 2: Calculate layout ----
        // Calculate the size (in bytes, sector-aligned) of each directory's record area
        for (dir in directories) {
            dir.sizeInBytes = calculateDirectorySize(dir)
        }

        val lPathTableSector = 18
        val mPathTableSector = 19
        var nextSector = 20

        // Assign sectors to each directory
        for (dir in directories) {
            dir.sector = nextSector
            nextSector += dir.sectorCount
        }

        // Assign sectors to each file
        val allFiles = mutableListOf<DirEntryInfo.File>()
        for (dir in directories) {
            for (entry in dir.entries) {
                if (entry is DirEntryInfo.File) {
                    val sectorCount = ((entry.isoFile.data.size + SECTOR_SIZE - 1) / SECTOR_SIZE).coerceAtLeast(1)
                    entry.sector = nextSector
                    entry.sectorCount = sectorCount
                    nextSector += sectorCount
                    allFiles.add(entry)
                }
            }
        }

        val totalSectors = nextSector
        val pathTableSize = calculatePathTableSize(directories)
        val rootDir = directories[0]

        val writer = ByteWriter(totalSectors * SECTOR_SIZE)

        // ---- Phase 3: Write ----

        // ---- System Area (sectors 0-15) ----
        writer.writeZeroPadding(SECTOR_SIZE * SYSTEM_AREA_SECTORS)

        // ---- Primary Volume Descriptor (sector 16) ----
        val pvdStart = writer.size
        writer.writeByte(0x01) // Type Code: Primary Volume Descriptor
        writeAscii(writer, "CD001") // Standard Identifier
        writer.writeByte(0x01) // Version
        writer.writeByte(0x00) // Unused Field
        writePaddedString(writer, systemId, 32) // System Identifier
        writePaddedString(writer, volumeId, 32) // Volume Identifier
        writer.writeZeroPadding(8) // Unused Field
        writeBothEndian32(writer, totalSectors) // Volume Space Size
        writer.writeZeroPadding(32) // Unused Field
        writeBothEndian16(writer, 1) // Volume Set Size
        writeBothEndian16(writer, 1) // Volume Sequence Number
        writeBothEndian16(writer, SECTOR_SIZE) // Logical Block Size
        writeBothEndian32(writer, pathTableSize) // Path Table Size
        writeInt32LE(writer, lPathTableSector) // Type L Path Table Location
        writeInt32LE(writer, 0) // Optional Type L Path Table Location
        writeInt32BE(writer, mPathTableSector) // Type M Path Table Location
        writeInt32BE(writer, 0) // Optional Type M Path Table Location

        // Root Directory Record (inline in PVD, 34 bytes)
        writeDirectoryRecord(writer, rootDir.sector, rootDir.sizeInBytes, 0x02, byteArrayOf(0x00))

        writePaddedString(writer, "", 128) // Volume Set Identifier
        writePaddedString(writer, "", 128) // Publisher Identifier
        writePaddedString(writer, "", 128) // Data Preparer Identifier
        writePaddedString(writer, "BUTTERSCOTCH PREPROCESSOR", 128) // Application Identifier
        writePaddedString(writer, "", 37) // Copyright File Identifier
        writePaddedString(writer, "", 37) // Abstract File Identifier
        writePaddedString(writer, "", 37) // Bibliographic File Identifier
        writePvdDateTime(writer) // Volume Creation Date and Time
        writePvdDateTime(writer) // Volume Modification Date and Time
        writeEmptyPvdDateTime(writer) // Volume Expiration Date and Time
        writePvdDateTime(writer) // Volume Effective Date and Time
        writer.writeByte(0x01) // File Structure Version
        writer.writeByte(0x00) // Reserved

        // Pad remainder of PVD sector
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - pvdStart))

        // ---- Volume Descriptor Set Terminator (sector 17) ----
        val termStart = writer.size
        writer.writeByte(0xFF) // Type Code: Terminator
        writeAscii(writer, "CD001") // Standard Identifier
        writer.writeByte(0x01) // Version
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - termStart))

        // ---- L Path Table (sector 18, Little Endian) ----
        val lptStart = writer.size
        writePathTable(writer, directories, littleEndian = true)
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - lptStart))

        // ---- M Path Table (sector 19, Big Endian) ----
        val mptStart = writer.size
        writePathTable(writer, directories, littleEndian = false)
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - mptStart))

        // ---- Directory Records ----
        for (dir in directories) {
            val dirStart = writer.size
            val parentDir = directories[dir.parentIndex]

            // "." entry (self)
            writeDirectoryRecord(writer, dir.sector, dir.sizeInBytes, 0x02, byteArrayOf(0x00))

            // ".." entry (parent)
            writeDirectoryRecord(writer, parentDir.sector, parentDir.sizeInBytes, 0x02, byteArrayOf(0x01))

            // Child entries (subdirectories and files)
            for (entry in dir.entries) {
                val identifier: ByteArray
                val entrySector: Int
                val dataLength: Int
                val flags: Int

                when (entry) {
                    is DirEntryInfo.Subdirectory -> {
                        val childDir = directories[entry.dirIndex]
                        identifier = entry.name.encodeToByteArray()
                        entrySector = childDir.sector
                        dataLength = childDir.sizeInBytes
                        flags = 0x02
                    }
                    is DirEntryInfo.File -> {
                        identifier = "${entry.name};1".encodeToByteArray()
                        entrySector = entry.sector
                        dataLength = entry.isoFile.data.size
                        flags = 0x00
                    }
                }

                // Directory records cannot span sector boundaries
                val baseLength = 33 + identifier.size
                val paddingByte = if (baseLength % 2 != 0) 1 else 0
                val recordLength = baseLength + paddingByte
                val sectorOffset = (writer.size - dirStart) % SECTOR_SIZE
                if (sectorOffset + recordLength > SECTOR_SIZE) {
                    writer.writeZeroPadding(SECTOR_SIZE - sectorOffset)
                }

                writeDirectoryRecord(writer, entrySector, dataLength, flags, identifier)
            }

            // Pad to sector boundary
            val written = writer.size - dirStart
            val totalDirSize = dir.sectorCount * SECTOR_SIZE
            if (totalDirSize > written) {
                writer.writeZeroPadding(totalDirSize - written)
            }
        }

        // ---- File Data ----
        for (entry in allFiles) {
            writer.writeByteArray(entry.isoFile.data)
            val targetSize = entry.sectorCount * SECTOR_SIZE
            val padding = targetSize - entry.isoFile.data.size
            if (padding > 0) {
                writer.writeZeroPadding(padding)
            }
        }

        return writer.getAsByteArray()
    }

    private fun calculateDirectorySize(dir: DirInfo): Int {
        var size = 0
        size += 34 // "." entry (33 + 1 byte identifier)
        size += 34 // ".." entry (33 + 1 byte identifier)

        for (entry in dir.entries) {
            val identifier = when (entry) {
                is DirEntryInfo.Subdirectory -> entry.name.encodeToByteArray()
                is DirEntryInfo.File -> "${entry.name};1".encodeToByteArray()
            }
            val baseLength = 33 + identifier.size
            val paddingByte = if (baseLength % 2 != 0) 1 else 0
            val recordLength = baseLength + paddingByte

            // Directory records cannot span sector boundaries
            val sectorOffset = size % SECTOR_SIZE
            if (sectorOffset + recordLength > SECTOR_SIZE) {
                size += SECTOR_SIZE - sectorOffset
            }
            size += recordLength
        }

        // Round up to sector boundary
        return ((size + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE
    }

    private fun calculatePathTableSize(directories: List<DirInfo>): Int {
        var size = 0
        for (dir in directories) {
            // Root has identifier length 1 (byte 0x00), others use the directory name
            val nameLength = if (dir.name.isEmpty()) 1 else dir.name.length
            // 8 fixed bytes + identifier + optional padding to even length
            size += 8 + nameLength + (if (nameLength % 2 != 0) 1 else 0)
        }
        return size
    }

    private fun writePathTable(writer: ByteWriter, directories: List<DirInfo>, littleEndian: Boolean) {
        for (dir in directories) {
            val nameBytes: ByteArray
            val nameLength: Int
            if (dir.name.isEmpty()) {
                // Root directory identifier
                nameBytes = byteArrayOf(0x00)
                nameLength = 1
            } else {
                nameBytes = dir.name.encodeToByteArray()
                nameLength = nameBytes.size
            }

            writer.writeByte(nameLength) // Length of Directory Identifier
            writer.writeByte(0x00) // Extended Attribute Record Length
            if (littleEndian) {
                writeInt32LE(writer, dir.sector)
                writeInt16LE(writer, dir.parentIndex + 1) // 1-based directory number
            } else {
                writeInt32BE(writer, dir.sector)
                writeInt16BE(writer, dir.parentIndex + 1) // 1-based directory number
            }
            writer.writeByteArray(nameBytes)
            if (nameLength % 2 != 0) {
                writer.writeByte(0x00) // Padding
            }
        }
    }

    private fun writeDirectoryRecord(
        writer: ByteWriter,
        extentSector: Int,
        dataLength: Int,
        flags: Int,
        identifier: ByteArray
    ) {
        // Record length must be even
        val baseLength = 33 + identifier.size
        val paddingByte = if (baseLength % 2 != 0) 1 else 0
        val recordLength = baseLength + paddingByte

        writer.writeByte(recordLength) // Length of Directory Record
        writer.writeByte(0x00) // Extended Attribute Record Length
        writeBothEndian32(writer, extentSector) // Location of Extent
        writeBothEndian32(writer, dataLength) // Data Length
        writeDirectoryDateTime(writer) // Recording Date and Time
        writer.writeByte(flags) // File Flags
        writer.writeByte(0x00) // File Unit Size
        writer.writeByte(0x00) // Interleave Gap Size
        writeBothEndian16(writer, 1) // Volume Sequence Number
        writer.writeByte(identifier.size) // Length of File Identifier
        writer.writeByteArray(identifier) // File Identifier
        if (paddingByte > 0) {
            writer.writeByte(0x00) // Padding
        }
    }

    // Both-endian (LE then BE) 32-bit integer
    private fun writeBothEndian32(writer: ByteWriter, value: Int) {
        writeInt32LE(writer, value)
        writeInt32BE(writer, value)
    }

    // Both-endian (LE then BE) 16-bit integer
    private fun writeBothEndian16(writer: ByteWriter, value: Int) {
        writeInt16LE(writer, value)
        writeInt16BE(writer, value)
    }

    private fun writeInt32LE(writer: ByteWriter, value: Int) {
        writer.writeByte(value and 0xFF)
        writer.writeByte((value shr 8) and 0xFF)
        writer.writeByte((value shr 16) and 0xFF)
        writer.writeByte((value shr 24) and 0xFF)
    }

    private fun writeInt32BE(writer: ByteWriter, value: Int) {
        writer.writeByte((value shr 24) and 0xFF)
        writer.writeByte((value shr 16) and 0xFF)
        writer.writeByte((value shr 8) and 0xFF)
        writer.writeByte(value and 0xFF)
    }

    private fun writeInt16LE(writer: ByteWriter, value: Int) {
        writer.writeByte(value and 0xFF)
        writer.writeByte((value shr 8) and 0xFF)
    }

    private fun writeInt16BE(writer: ByteWriter, value: Int) {
        writer.writeByte((value shr 8) and 0xFF)
        writer.writeByte(value and 0xFF)
    }

    private fun writeAscii(writer: ByteWriter, str: String) {
        writer.writeByteArray(str.encodeToByteArray())
    }

    // Write a string padded with spaces to the given length
    private fun writePaddedString(writer: ByteWriter, str: String, length: Int) {
        val bytes = str.encodeToByteArray()
        val toCopy = bytes.size.coerceAtMost(length)
        for (i in 0 until toCopy) {
            writer.writeByte(bytes[i].toInt())
        }
        for (i in toCopy until length) {
            writer.writeByte(0x20) // Space
        }
    }

    // PVD date/time format: 17 ASCII bytes "YYYYMMDDHHmmsscc" + 1 byte GMT offset
    private fun writePvdDateTime(writer: ByteWriter) {
        writeAscii(writer, "2026031500000000") // 2026-03-15 00:00:00.00
        writer.writeByte(0x00) // GMT offset
    }

    // Empty PVD date/time (all zeros means not specified)
    private fun writeEmptyPvdDateTime(writer: ByteWriter) {
        for (i in 0 until 17) {
            writer.writeByte(0x30) // '0'
        }
    }

    // Directory record date/time: 7 binary bytes
    private fun writeDirectoryDateTime(writer: ByteWriter) {
        writer.writeByte(126) // Years since 1900 (2026)
        writer.writeByte(3)   // Month
        writer.writeByte(15)  // Day
        writer.writeByte(0)   // Hour
        writer.writeByte(0)   // Minute
        writer.writeByte(0)   // Second
        writer.writeByte(0)   // GMT offset (15-min intervals)
    }
}
