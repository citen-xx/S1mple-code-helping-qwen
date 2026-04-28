DELETE FROM test_case;
DELETE FROM question;

ALTER TABLE question AUTO_INCREMENT = 1;
ALTER TABLE test_case AUTO_INCREMENT = 1;

INSERT INTO question (id, title, content, difficulty, time_limit, memory_limit)
VALUES
    (
        1,
        'A + B Problem',
        'Given two integers a and b, output their sum. The input contains one line with two integers. Output one integer representing a + b.',
        'Easy',
        1000,
        128
    ),
    (
        2,
        'Palindrome String',
        'Given a string s containing only lowercase letters, determine whether it is a palindrome. If it is, output yes. Otherwise, output no.',
        'Easy',
        1000,
        128
    ),
    (
        3,
        'Binary Search in Sorted Array',
        'Given a sorted integer array and a target value, output the index of target starting from 0. If target does not exist, output -1. The first line contains n and target. The second line contains n sorted integers.',
        'Medium',
        1000,
        256
    );

INSERT INTO test_case (question_id, input, expected_output)
VALUES
    (1, '1 2', '3'),
    (1, '100 250', '350'),
    (1, '-5 7', '2'),

    (2, 'level', 'yes'),
    (2, 'hello', 'no'),
    (2, 'abba', 'yes'),

    (3, '5 7\n1 3 5 7 9', '3'),
    (3, '6 4\n1 2 3 5 6 8', '-1'),
    (3, '1 10\n10', '0');
