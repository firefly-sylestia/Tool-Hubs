package com.sylestia.toolhubs.musicanalyzer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch as coroutineLaunch

// Compatibility overloads for mixed Int/Double max() calls in MainActivity.
private fun mixedMax(a: Int, b: Double): Double = kotlin.math.max(a.toDouble(), b)

// Keep the existing MainActivity source unchanged while providing the missing
// coroutine builder in this package.
fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job = coroutineLaunch(block)

// Package-level overloads used by MainActivity's existing unqualified max() calls.
fun max(a: Int, b: Double): Double = mixedMax(a, b)
fun max(a: Double, b: Int): Double = kotlin.math.max(a, b.toDouble())
