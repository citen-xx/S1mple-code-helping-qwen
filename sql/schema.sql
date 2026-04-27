CREATE TABLE IF NOT EXISTS question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    time_limit INT NOT NULL COMMENT 'time limit in ms',
    memory_limit INT NOT NULL COMMENT 'memory limit in MB'
);

CREATE TABLE IF NOT EXISTS test_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    input TEXT NOT NULL,
    expected_output TEXT NOT NULL,
    CONSTRAINT fk_test_case_question FOREIGN KEY (question_id) REFERENCES question (id) ON DELETE CASCADE
);
