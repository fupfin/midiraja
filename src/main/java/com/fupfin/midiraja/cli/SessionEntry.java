package com.fupfin.midiraja.cli;

import java.time.Instant;
import java.util.List;

record SessionEntry(List<String> args, Instant savedAt) {}
