@main def main: Unit =
  type T = 3f
  val value0: T = -3.5f // error
  val value1: T = -100500 // error
  val value2: T = -100500L // error
  val value3: T = -100500D // error
  val value4: T = true // error
  val value5: 3f = -100500 // error
  val value6: 3f = -100500L // error