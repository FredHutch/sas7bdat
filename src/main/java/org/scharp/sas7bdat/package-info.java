///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
/**
 * <p>
 * This library writes SAS7BDAT files that can be read by SAS.
 * </p>
 *
 * <p>
 * See the documentation for {@link org.scharp.sas7bdat.Sas7bdatExporter} for sample code on writing a SAS7BDAT.
 * </p>
 *
 * <h2>A SAS Primer for Java Programmers</h2>
 *
 * <p>
 * In SAS, a row of data is called an "observation". Observations that share an identical structure are organized into
 * "datasets". Zero or more SAS datasets are organized into a SAS library, which typically corresponds to all SAS7BDAT
 * files within a directory, although other library formats do exist.
 * </p>
 *
 * <p>
 * The SAS7BDAT format is not publicly documented, but it was reverse engineered by Dr. Matthew Shotwell and others to
 * be able to read SAS7BDAT from R.  The document is available at <a
 * href="https://cran.r-project.org/web/packages/sas7bdat/sas7bdat.pdf">https://cran.r-project.org/web/packages/sas7bdat/sas7bdat.pdf</a>
 * </p>
 *
 * <p>
 * SAS is loosely typed. It has only two types: "numeric" and "character". Numeric data is always persisted as double
 * precision floating point values. Character data is persisted as an array of bytes using a character coding that must
 * be managed by the programmer.  All of the size limits on strings are in bytes, not characters.  SAS datasets
 * typically use ASCII or WINDOWS-1252, so this distinction not usually significant.  This library is hard-coded to use
 * UTF-8.
 * </p>
 *
 * <p>
 * Higher level types, such as "currency", "dates", or "times" are merely formats on these two base types. For example,
 * the number "1", could be "$1.00", "2-JAN-1960", or "1-JAN-1960:00:00:01" depending on the format. SAS has over 100
 * built-in formats, many of which are only slight variations of each other, but the format system is extensible. For
 * example, if a SAS programmer wanted to create the equivalent of an {@code enum}, they could do so with a user-defined
 * format. For example, they might create an enum for "smoking status" and format 1 as "Never Smoked", 2 as "Quit", and
 * 3 as "Active Smoker". The name of this format can be persisted in an SAS7BDAT, but the formatting cannot. So any code
 * which processes the dataset will only see that the data has the SMOKER format and has data "1.0", "2.0", and "3.0"
 * (remember, all numeric values are double-precision floating point).
 * </p>
 *
 * <p>
 * The SAS7BDAT format is platform-dependent and is optimized for the local machine's architecture.  A SAS installation
 * on Windows will generate a different file than one on a 64-bit UNIX host.  (This was more important in the 1960s and
 * 1970s, when computers had more varied architectures, different endianness, different word sizes, and different
 * floating point representations.  During those decades, transferring files between different machines was rare but
 * marshalling data into a standard format was computationally prohibitive).  Modern SAS installations can read a
 * SAS7BDAT file generated on a different platform with some degraded functionality.  This library is hard-coded to
 * write for a 64-bit, little-endian CPU with IEEE 754 floating point representation.
 * </p>
 *
 * <p>
 * SAS's legacy imposes some strict limitations.  For example, in version 5, a variable name must not exceed 8 bytes. In
 * version 7, this was expanded to 32 bytes.  If a SAS program defines a system option
 * {@code OPTIONS VALIDVARNAME=ANY;}, a variable name can also include spaces and non-ASCII characters. This library is
 * as permissive as SAS its most permissive mode.
 * </p>
 *
 * <h2>Error Handling Strategy</h2>
 * <p>
 * This library enforces strict input checking to prevent unintentional data loss or unintentionally creating a
 * malformed SAS7BDAT file.  It throws clear exceptions as soon as possible (fail-fast). In most of these cases, callers
 * can work around the problem by having partial data loss (simple truncation), if that's desirable for their particular
 * use case.
 * </p>
 */
package org.scharp.sas7bdat;